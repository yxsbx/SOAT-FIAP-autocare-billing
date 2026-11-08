package br.com.autocarehub.billing;

import br.com.autocarehub.telemetry.MessageTrace;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnProperty(name = "billing.mercado-pago.enabled", havingValue = "true")
class PaymentWorkflow {
    static final String REPAIR_REFUNDS = "autocare.billing.refund-repair.v1";
    static final String PAYMENT_RESULTS = "autocare.os.payment-updated.v1";
    private static final int MAX_CREATION_ATTEMPTS = 5;
    private static final Logger LOG = LoggerFactory.getLogger(PaymentWorkflow.class);
    private static final String PAYMENT =
            """
            SELECT p.budget_id, b.order_id, b.total_amount, p.status, p.provider_order_id,
                   p.creation_attempts, p.updated_at, p.refund_requested_at, p.refund_submitted, p.trace_context
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
                WHERE p.status = 'PENDING_CREATION' AND NOT p.creation_review_required
                    AND p.next_attempt_at <= CURRENT_TIMESTAMP
                ORDER BY p.next_attempt_at, p.budget_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """)
                .query(PaymentWorkflow::map)
                .optional()
                .ifPresent(payment -> withTrace(payment, () -> create(payment)));
    }

    private void create(Payment payment) {
        if (payment.amount().signum() == 0) {
            recordStatus(payment, "CONFIRMED");
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (!now.isBefore(payment.createdAt().plusMinutes(15))) {
            requireCreationReview(payment);
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
            int attempts = payment.attempts() + 1;
            jdbc.sql(
                            """
                    UPDATE payments SET creation_attempts = ?, next_attempt_at = ?
                    WHERE budget_id = ?
                    """)
                    .params(attempts, now.plusSeconds(30), payment.budgetId())
                    .update();
            if (attempts >= MAX_CREATION_ATTEMPTS) {
                requireCreationReview(payment);
            }
            return;
        }
        recordProviderOrder(payment, order);
        applyProviderStatus(payment, order);
    }

    private void requireCreationReview(Payment payment) {
        jdbc.sql("UPDATE payments SET creation_review_required = TRUE WHERE budget_id = ?")
                .param(payment.budgetId())
                .update();
        LOG.warn("mercado_pago_creation_review_required budgetId={}", payment.budgetId());
    }

    private void recordProviderOrder(Payment payment, MercadoPagoOrders.ProviderOrder order) {
        jdbc.sql(
                        """
                UPDATE payments SET provider_order_id = ?, qr_data = ?, status = 'AWAITING_PAYMENT',
                                    creation_review_required = FALSE, updated_at = CURRENT_TIMESTAMP
                WHERE budget_id = ?
                """)
                .params(order.id(), order.qrData(), payment.budgetId())
                .update();
    }

    @RabbitListener(queues = REPAIR_REFUNDS)
    @Transactional
    public void requestRefund(String payload) {
        var request = json.readValue(payload, RepairRefund.class);
        var payment = jdbc.sql(PAYMENT + "WHERE p.budget_id = ? AND b.order_id = ? FOR UPDATE")
                .params(request.budgetId(), request.orderId())
                .query(PaymentWorkflow::map)
                .single();
        if (payment.refundRequestedAt() != null) {
            return;
        }
        if (!List.of("CONFIRMED", "REFUNDED").contains(payment.status())) {
            throw new IllegalStateException("A repair refund requires a confirmed payment");
        }
        jdbc.sql(
                        """
                UPDATE payments SET refund_requested_at = CURRENT_TIMESTAMP,
                                    refund_next_attempt_at = CURRENT_TIMESTAMP, trace_context = ?
                WHERE budget_id = ?
                """)
                .params(MessageTrace.capture(), payment.budgetId())
                .update();
        if ("REFUNDED".equals(payment.status())) {
            recordStatus(payment, "REFUNDED");
        }
    }

    @Scheduled(fixedDelayString = "${billing.mercado-pago.poll-delay-ms:2000}")
    @Transactional
    public void processRefund() {
        jdbc.sql(
                        PAYMENT
                                + """
                WHERE p.status = 'CONFIRMED' AND NOT p.refund_review_required
                    AND p.refund_next_attempt_at <= CURRENT_TIMESTAMP
                ORDER BY p.refund_next_attempt_at, p.budget_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """)
                .query(PaymentWorkflow::map)
                .optional()
                .ifPresent(payment -> withTrace(payment, () -> refund(payment)));
    }

    private void refund(Payment payment) {
        if (payment.amount().signum() == 0) {
            recordStatus(payment, "REFUNDED");
            return;
        }
        try {
            var order = provider.get(payment.providerOrderId());
            if (!payment.providerOrderId().equals(order.id()) || !order.matches(payment.budgetId(), payment.amount())) {
                throw new IllegalStateException("Unexpected Mercado Pago refund order");
            }
            if ("refunded".equals(order.status())) {
                recordStatus(payment, "REFUNDED");
                return;
            }
            if (reviewRefundIfExpired(payment)) {
                return;
            }
            if (order.accredited() && !order.refundInProgress() && !payment.refundSubmitted()) {
                provider.refund(order.id(), payment.budgetId());
                jdbc.sql("UPDATE payments SET refund_submitted = TRUE WHERE budget_id = ?")
                        .param(payment.budgetId())
                        .update();
            }
        } catch (RuntimeException failure) {
            LOG.warn(
                    "mercado_pago_refund_pending budgetId={} errorType={}",
                    payment.budgetId(),
                    failure.getClass().getSimpleName());
            if (reviewRefundIfExpired(payment)) {
                return;
            }
        }
        jdbc.sql("UPDATE payments SET refund_next_attempt_at = ? WHERE budget_id = ?")
                .params(OffsetDateTime.now().plusSeconds(30), payment.budgetId())
                .update();
    }

    private boolean reviewRefundIfExpired(Payment payment) {
        if (OffsetDateTime.now().isBefore(payment.refundRequestedAt().plusMinutes(15))) {
            return false;
        }
        jdbc.sql("UPDATE payments SET refund_review_required = TRUE WHERE budget_id = ?")
                .param(payment.budgetId())
                .update();
        LOG.warn("mercado_pago_refund_review_required budgetId={}", payment.budgetId());
        return true;
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
                    if (!order.matches(payment.budgetId(), payment.amount())
                            || (payment.providerOrderId() != null
                                    && !providerOrderId.equals(payment.providerOrderId()))) {
                        throw new IllegalStateException("Mercado Pago amount or reference does not match the budget");
                    }
                    if (payment.providerOrderId() == null
                            && "PENDING_CREATION".equals(payment.status())
                            && (!"created".equals(order.status())
                                    || !order.qrData().isBlank())) {
                        recordProviderOrder(payment, order);
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
                } else if ("refunded".equals(order.status())) {
                    recordStatus(payment, "REFUNDED");
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

    private void withTrace(Payment payment, Runnable operation) {
        try (var scope = MessageTrace.restore(payment.traceContext())) {
            operation.run();
        }
    }

    private void recordStatus(Payment payment, String status) {
        jdbc.sql("UPDATE payments SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE budget_id = ?")
                .params(status, payment.budgetId())
                .update();
        var payload = new PaymentResult(payment.budgetId(), payment.orderId(), payment.amount(), status);
        jdbc.sql("INSERT INTO billing_outbox(event_id, routing_key, payload, trace_context) VALUES (?, ?, ?, ?)")
                .params(UUID.randomUUID(), PAYMENT_RESULTS, json.writeValueAsString(payload), MessageTrace.capture())
                .update();
        LOG.info(
                "payment_update_staged orderId={} budgetId={} status={}",
                payment.orderId(),
                payment.budgetId(),
                status);
    }

    private static Payment map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Payment(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getBigDecimal(3),
                rs.getString(4),
                rs.getString(5),
                rs.getInt(6),
                rs.getObject(7, OffsetDateTime.class),
                rs.getObject(8, OffsetDateTime.class),
                rs.getBoolean(9),
                rs.getString(10));
    }

    private record Payment(
            UUID budgetId,
            UUID orderId,
            BigDecimal amount,
            String status,
            String providerOrderId,
            int attempts,
            OffsetDateTime createdAt,
            OffsetDateTime refundRequestedAt,
            boolean refundSubmitted,
            String traceContext) {}

    private record RepairRefund(UUID orderId, UUID budgetId) {
        private RepairRefund {
            Objects.requireNonNull(orderId);
            Objects.requireNonNull(budgetId);
        }
    }

    private record PaymentResult(UUID budgetId, UUID orderId, BigDecimal totalAmount, String status) {}
}
