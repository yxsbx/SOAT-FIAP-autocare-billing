package br.com.autocarehub.billing;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

@Component
@ConditionalOnProperty(name = "billing.mercado-pago.enabled", havingValue = "true")
class MercadoPagoOrders {
    private final RestClient client;
    private final String pointOfSale;

    MercadoPagoOrders(
            @Value("${MERCADO_PAGO_ACCESS_TOKEN}") String token,
            @Value("${MERCADO_PAGO_EXTERNAL_POS_ID}") String pointOfSale,
            @Value("${MERCADO_PAGO_BASE_URL:https://api.mercadopago.com}") URI base) {
        if (token.isBlank() || pointOfSale.isBlank()) {
            throw new IllegalArgumentException("Mercado Pago access token and point of sale are required");
        }
        if (!"https".equals(base.getScheme()) && !"127.0.0.1".equals(base.getHost())) {
            throw new IllegalArgumentException("Mercado Pago endpoint requires HTTPS");
        }
        this.pointOfSale = pointOfSale;
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(8));
        this.client = RestClient.builder()
                .baseUrl(base.toString())
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + token)
                .build();
    }

    ProviderOrder create(UUID budgetId, BigDecimal amount) {
        String value = amount.toPlainString();
        var body = Map.of(
                "type",
                "qr",
                "total_amount",
                value,
                "external_reference",
                budgetId.toString(),
                "description",
                "Orcamento AutoCare",
                "config",
                Map.of("qr", Map.of("external_pos_id", pointOfSale, "mode", "dynamic")),
                "transactions",
                Map.of("payments", List.of(Map.of("amount", value))));
        var response = client.post()
                .uri("/v1/orders")
                .header("X-Idempotency-Key", budgetId.toString())
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        return parse(response);
    }

    ProviderOrder get(String providerOrderId) {
        return parse(
                client.get().uri("/v1/orders/{id}", providerOrderId).retrieve().body(JsonNode.class));
    }

    void refund(String providerOrderId, UUID budgetId) {
        var key = UUID.nameUUIDFromBytes(("refund:" + budgetId).getBytes(StandardCharsets.UTF_8));
        client.post()
                .uri("/v1/orders/{id}/refund", providerOrderId)
                .header("X-Idempotency-Key", key.toString())
                .retrieve()
                .toBodilessEntity();
    }

    private ProviderOrder parse(JsonNode response) {
        if (response == null
                || !"qr".equals(response.path("type").asString())
                || !"BRL".equals(response.path("currency").asString())) {
            throw new IllegalStateException("Mercado Pago returned an unexpected order");
        }
        var payment = response.path("transactions").path("payments").path(0);
        boolean refundInProgress = false;
        for (JsonNode refund : response.path("transactions").path("refunds")) {
            if (List.of("processing", "processed")
                    .contains(refund.path("status").asString())) {
                refundInProgress = true;
            }
        }
        return new ProviderOrder(
                response.path("id").asString(),
                response.path("external_reference").asString(),
                new BigDecimal(response.path("total_amount").asString()),
                new BigDecimal(payment.path("amount").asString()),
                response.path("status").asString(),
                payment.path("status").asString(),
                payment.path("status_detail").asString(),
                response.path("type_response").path("qr_data").asString(""),
                refundInProgress);
    }

    record ProviderOrder(
            String id,
            String reference,
            BigDecimal total,
            BigDecimal paymentAmount,
            String status,
            String paymentStatus,
            String paymentDetail,
            String qrData,
            boolean refundInProgress) {
        boolean matches(UUID budgetId, BigDecimal amount) {
            return id.matches("[A-Za-z0-9_-]{1,128}")
                    && budgetId.toString().equals(reference)
                    && total.compareTo(amount) == 0
                    && paymentAmount.compareTo(amount) == 0;
        }

        boolean accredited() {
            return "processed".equals(status)
                    && "processed".equals(paymentStatus)
                    && "accredited".equals(paymentDetail);
        }
    }
}
