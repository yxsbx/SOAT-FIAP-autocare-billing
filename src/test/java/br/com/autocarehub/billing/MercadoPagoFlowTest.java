package br.com.autocarehub.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import tools.jackson.databind.json.JsonMapper;

class MercadoPagoFlowTest {
    private static final String ORDER = "ORDTEST001";
    private static final String SECRET = "local-webhook-secret";
    private static final String REQUEST_ID = "webhook-01";
    private static final String TS = "1700000000000";
    private final UUID budgetId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile String status = "created";
    private volatile String paymentDetail = "ready_to_process";
    private volatile String providerAmount = "75.00";
    private volatile boolean rejectCreate;
    private volatile String sentAuthorization;
    private volatile String sentKey;
    private volatile String sentBody;
    private EmbeddedDatabase db;
    private JdbcClient jdbc;
    private HttpServer server;
    private URI address;
    private PaymentWorkflow payments;
    private MercadoPagoWebhook webhook;

    @BeforeEach
    void setup() throws Exception {
        db = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName(UUID.randomUUID() + ";MODE=PostgreSQL")
                .addScripts(
                        "db/migration/V1__create_budgets.sql",
                        "db/migration/V2__budget_decisions.sql",
                        "db/migration/V3__payments.sql")
                .build();
        jdbc = JdbcClient.create(db);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/orders", this::provider);
        server.start();
        address = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        payments = new PaymentWorkflow(jdbc, new MercadoPagoOrders("test-token", "test-pos", address), json);
        webhook = new MercadoPagoWebhook(SECRET, payments);
    }

    @AfterEach
    void close() {
        server.stop(0);
        db.shutdown();
    }

    @Test
    void createsOneQrAndReconcilesOnlyTheAccreditedAmount() throws Exception {
        approve("75.00");
        payments.createPending();
        payments.createPending();

        assertThat(calls.get()).isEqualTo(1);
        assertThat(sentAuthorization).isEqualTo("Bearer test-token");
        assertThat(sentKey).isEqualTo(budgetId.toString());
        assertThat(json.readTree(sentBody)
                        .path("config")
                        .path("qr")
                        .path("mode")
                        .asString())
                .isEqualTo("dynamic");
        var query = new BudgetQueryController(jdbc, "internal");
        assertThat(query.get(budgetId, "internal").qrData()).isEqualTo("QR-DE-TESTE");
        assertThat(query.get(budgetId, "internal").paymentStatus()).isEqualTo("AWAITING_PAYMENT");

        assertThatThrownBy(() -> webhook.receive(ORDER, "v1=invalid", REQUEST_ID))
                .hasMessageContaining("401");
        assertThatThrownBy(() -> webhook.receive(ORDER, "ts=" + TS + ",v1=" + "0".repeat(64), REQUEST_ID))
                .hasMessageContaining("401");
        status = "processed";
        deliverWebhook();
        assertThat(status()).isEqualTo("AWAITING_PAYMENT");
        paymentDetail = "accredited";
        providerAmount = "76.00";
        assertThatThrownBy(this::deliverWebhook).hasMessageContaining("amount or reference");
        assertThat(status()).isEqualTo("AWAITING_PAYMENT");
        providerAmount = "75.00";
        deliverWebhook();
        deliverWebhook();
        assertThat(status()).isEqualTo("CONFIRMED");
        assertThat(resultEvents()).hasSize(1);
        assertThat(json.readTree(resultEvents().getFirst()).path("status").asString())
                .isEqualTo("CONFIRMED");

        status = "refunded";
        deliverWebhook();
        assertThat(status()).isEqualTo("REFUNDED");
        assertThat(resultEvents()).hasSize(2);
    }

    @Test
    void retriesProviderErrorsAndEmitsExpiration() throws Exception {
        approve("75.00");
        rejectCreate = true;
        payments.createPending();
        payments.createPending();
        assertThat(calls.get()).isEqualTo(1);
        assertThat(status()).isEqualTo("PENDING_CREATION");
        jdbc.sql("UPDATE payments SET next_attempt_at = CURRENT_TIMESTAMP").update();
        rejectCreate = false;
        payments.createPending();
        status = "expired";
        deliverWebhook();
        deliverWebhook();
        assertThat(status()).isEqualTo("EXPIRED");
        assertThat(resultEvents()).hasSize(1);
    }

    @Test
    void confirmsZeroAmountWithoutExternalCharge() {
        approve("0.00");
        payments.createPending();
        assertThat(calls.get()).isZero();
        assertThat(status()).isEqualTo("CONFIRMED");
        assertThat(resultEvents()).hasSize(1);
    }

    @Test
    void recordsProviderCancellation() throws Exception {
        approve("75.00");
        payments.createPending();
        status = "canceled";
        deliverWebhook();
        assertThat(status()).isEqualTo("CANCELED");
        assertThat(resultEvents()).hasSize(1);
    }

    @Test
    void rejectsUnsafeProviderConfiguration() {
        assertThatThrownBy(() -> new MercadoPagoOrders("", "pos", address))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MercadoPagoOrders("token", "pos", URI.create("http://example.org")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MercadoPagoWebhook("", payments)).isInstanceOf(IllegalArgumentException.class);
    }

    private void approve(String amount) {
        var consumer = new BudgetConsumer(jdbc, json);
        var item = new BudgetMessages.Item(UUID.randomUUID(), "Diagnóstico", 1, new BigDecimal(amount));
        consumer.generate(json.writeValueAsString(new BudgetMessages.Request(budgetId, orderId, List.of(item))));
        consumer.decide(json.writeValueAsString(
                new BudgetMessages.Decide(budgetId, orderId, BudgetMessages.Decision.APPROVED, "test", null)));
    }

    private String status() {
        return jdbc.sql("SELECT status FROM payments").query(String.class).single();
    }

    private List<String> resultEvents() {
        return jdbc.sql("SELECT payload FROM billing_outbox WHERE routing_key = ?")
                .param(PaymentWorkflow.PAYMENT_RESULTS)
                .query(String.class)
                .list();
    }

    private void deliverWebhook() throws Exception {
        String manifest = "id:ordtest001;request-id:" + REQUEST_ID + ";ts:" + TS + ";";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8)));
        webhook.receive(ORDER, "ts=" + TS + ",v1=" + signature, REQUEST_ID);
    }

    private void provider(HttpExchange exchange) throws IOException {
        try (exchange) {
            calls.incrementAndGet();
            if ("POST".equals(exchange.getRequestMethod())) {
                sentKey = exchange.getRequestHeaders().getFirst("X-Idempotency-Key");
                sentAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
                sentBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (rejectCreate) {
                    exchange.sendResponseHeaders(503, -1);
                    return;
                }
            }
            byte[] body =
                    """
                    {
                      "id":"%s","type":"qr","currency":"BRL","external_reference":"%s",
                      "total_amount":"%s","status":"%s","type_response":{"qr_data":"QR-DE-TESTE"},
                      "transactions":{"payments":[{"amount":"%s","status":"%s","status_detail":"%s"}]}
                    }
                    """
                            .formatted(ORDER, budgetId, providerAmount, status, providerAmount, status, paymentDetail)
                            .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
