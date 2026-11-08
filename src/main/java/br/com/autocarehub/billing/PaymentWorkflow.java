package br.com.autocarehub.billing;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnProperty(name = "billing.mercado-pago.enabled", havingValue = "true")
class PaymentWorkflow {
    static final String PAYMENT_RESULTS = "autocare.os.payment-updated.v1";
    private static final Logger LOG = LoggerFactory.getLogger(PaymentWorkflow.class);
    private static final String PAYMENT =
            """
            SELECT p.budget_id, b.order_id, b.total_amount, p.status, p.provider_order_id
            FROM payments p JOIN budgets b ON b.request_id = p.budget_id
            """;
    private final JdbcClient jdbc;
    private final MercadoPagoOrders provider;
    private final ObjectMapper json;

    PaymentWorkflow(JdbcClient jdbc, MercadoPagoOrders provider, ObjectMapper json) {
        this.jdbc = jdbc;
        this.provider = provider;
        this.json = json;
    }

    @Scheduled(fixedDelayString = "${billing.mercado-pago.poll-delay-ms:2000}")
    @Transactional
    public void createPending() {
        jdbc.sql(
                        PAYMENT
                                + """
                WHERE p.status = 'PENDING_CREATION' AND p.next_attempt_at <= CURRENT_TIMESTAMP
                ORDER BY p.next_attempt_at, p.budget_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """)
                .query(PaymentWorkflow::map)
                .optional()
                .ifPresent(this::create);
    }

    private void create(Payment payment) {
        if (payment.amount().signum() == 0) {
            recordStatus(payment, "CONFIRMED");
            return;
        }
        MercadoPagoOrders.ProviderOrder order;
        try {
            order = provider.create(payment.budgetId(), payment.amount());
            if (!order.matches(payment.budgetId(), payment.amount())
                    || ("created".equals(order.status()) && order.qrData().isBlank())) {
                throw new IllegalStateException("Invalid QR order returned by Mercado Pago");
            }
        } catch (RuntimeException failure) {
            LOG.warn(
                    "mercado_pago_create_failed budgetId={} errorType={}",
                    payment.budgetId(),
                    failure.getClass().getSimpleName());
            jdbc.sql("UPDATE payments SET next_attempt_at = ? WHERE budget_id = ?")
                    .params(OffsetDateTime.now().plusSeconds(30), payment.budgetId())
                    .update();
            return;
        }
        jdbc.sql(
                        """
                UPDATE payments SET provider_order_id = ?, qr_data = ?, status = 'AWAITING_PAYMENT',
                                    updated_at = CURRENT_TIMESTAMP
                WHERE budget_id = ?
                """)
                .params(order.id(), order.qrData(), payment.budgetId())
                .update();
        applyProviderStatus(payment, order);
    }

    @Transactional
    public void reconcile(String providerOrderId) {
        var order = provider.get(providerOrderId);
        if (!providerOrderId.equals(order.id())) {
            throw new IllegalStateException("Mercado Pago returned a different order");
        }
        UUID budgetId;
        try {
            budgetId = UUID.fromString(order.reference());
        } catch (IllegalArgumentException unrelated) {
            return;
        }
        jdbc.sql(PAYMENT + "WHERE p.budget_id = ? FOR UPDATE")
                .param(budgetId)
                .query(PaymentWorkflow::map)
                .optional()
                .ifPresent(payment -> {
                    if (!providerOrderId.equals(payment.providerOrderId())
                            || !order.matches(payment.budgetId(), payment.amount())) {
                        throw new IllegalStateException("Mercado Pago amount or reference does not match the budget");
                    }
                    applyProviderStatus(payment, order);
                });
    }

    private void applyProviderStatus(Payment payment, MercadoPagoOrders.ProviderOrder order) {
        switch (payment.status()) {
            case "PENDING_CREATION", "AWAITING_PAYMENT" -> {
                if (order.accredited()) {
                    recordStatus(payment, "CONFIRMED");
                } else if ("expired".equals(order.status())) {
                    recordStatus(payment, "EXPIRED");
                } else if ("canceled".equals(order.status())) {
                    recordStatus(payment, "CANCELED");
                }
            }
            case "CONFIRMED" -> {
                if ("refunded".equals(order.status())) {
                    recordStatus(payment, "REFUNDED");
                }
            }
            default -> {}
        }
    }

    private void recordStatus(Payment payment, String status) {
        jdbc.sql("UPDATE payments SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE budget_id = ?")
                .params(status, payment.budgetId())
                .update();
        var payload = new PaymentResult(payment.budgetId(), payment.orderId(), payment.amount(), status);
        jdbc.sql("INSERT INTO billing_outbox(event_id, routing_key, payload) VALUES (?, ?, ?)")
                .params(UUID.randomUUID(), PAYMENT_RESULTS, json.writeValueAsString(payload))
                .update();
    }

    private static Payment map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Payment(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getBigDecimal(3),
                rs.getString(4),
                rs.getString(5));
    }

    private record Payment(UUID budgetId, UUID orderId, BigDecimal amount, String status, String providerOrderId) {}

    private record PaymentResult(UUID budgetId, UUID orderId, BigDecimal totalAmount, String status) {}
}
