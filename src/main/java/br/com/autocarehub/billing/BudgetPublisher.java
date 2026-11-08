package br.com.autocarehub.billing;

import br.com.autocarehub.telemetry.MessageTrace;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class BudgetPublisher {
    private final JdbcClient jdbc;
    private final RabbitTemplate rabbit;

    BudgetPublisher(JdbcClient jdbc, RabbitTemplate rabbit) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
    }

    @Scheduled(fixedDelayString = "${billing.outbox.delay-ms:1000}")
    @Transactional
    public void publish() throws Exception {
        var pending = jdbc.sql(
                        """
                        SELECT request_id, response_payload, trace_context FROM budgets
                        WHERE published_at IS NULL ORDER BY created_at, request_id
                        LIMIT 1 FOR UPDATE SKIP LOCKED
                        """)
                .query((rs, row) -> new Pending(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)))
                .optional();
        if (pending.isPresent()) {
            var result = pending.get();
            send(BudgetMessages.RESULTS, result.payload(), result.traceContext());
            jdbc.sql("UPDATE budgets SET published_at = CURRENT_TIMESTAMP WHERE request_id = ?")
                    .param(result.id())
                    .update();
        }
    }

    @Scheduled(fixedDelayString = "${billing.outbox.delay-ms:1000}")
    @Transactional
    public void publishEvents() throws Exception {
        var pending = jdbc.sql(
                        """
                        SELECT event_id, routing_key, payload, trace_context FROM billing_outbox
                        WHERE published_at IS NULL ORDER BY created_at, event_id
                        LIMIT 1 FOR UPDATE SKIP LOCKED
                        """)
                .query((rs, row) ->
                        new Event(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)))
                .optional();
        if (pending.isPresent()) {
            var event = pending.get();
            send(event.routingKey(), event.payload(), event.traceContext());
            jdbc.sql("UPDATE billing_outbox SET published_at = CURRENT_TIMESTAMP WHERE event_id = ?")
                    .param(event.id())
                    .update();
        }
    }

    private void send(String routingKey, String payload, String traceContext) throws Exception {
        var confirmation = new CorrelationData(UUID.randomUUID().toString());
        try (var scope = MessageTrace.restore(traceContext)) {
            rabbit.convertAndSend(
                    "",
                    routingKey,
                    payload,
                    message -> {
                        message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        return message;
                    },
                    confirmation);
        }
        if (!confirmation.getFuture().get(5, TimeUnit.SECONDS).ack() || confirmation.getReturned() != null) {
            throw new IllegalStateException("RabbitMQ did not route the billing event");
        }
    }

    private record Event(UUID id, String routingKey, String payload, String traceContext) {}

    private record Pending(UUID id, String payload, String traceContext) {}
}
