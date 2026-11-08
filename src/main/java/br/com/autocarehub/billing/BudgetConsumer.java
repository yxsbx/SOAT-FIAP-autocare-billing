package br.com.autocarehub.billing;

import java.util.UUID;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
class BudgetConsumer {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    BudgetConsumer(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @RabbitListener(queues = BudgetMessages.REQUESTS)
    @Transactional
    public void generate(String payload) {
        var request = json.readValue(payload, BudgetMessages.Request.class);
        var total = request.totalAmount();
        var result = new BudgetMessages.Result(request.requestId(), request.orderId(), total);
        jdbc.sql(
                        """
                        INSERT INTO budgets(request_id, order_id, items, total_amount, response_payload)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT DO NOTHING
                        """)
                .params(
                        request.requestId(),
                        request.orderId(),
                        json.writeValueAsString(request.items()),
                        total,
                        json.writeValueAsString(result))
                .update();
    }

    @RabbitListener(queues = BudgetMessages.DECISIONS)
    @Transactional
    public void decide(String payload) {
        var request = json.readValue(payload, BudgetMessages.Decide.class);
        var decision = jdbc.sql("SELECT decision FROM budgets WHERE request_id = ? AND order_id = ? FOR UPDATE")
                .params(request.budgetId(), request.orderId())
                .query((rs, row) -> new RecordedDecision(rs.getString(1)))
                .single();
        if (decision.value() != null) {
            if (!decision.value().equals(request.decision().name())) {
                throw new IllegalStateException("The budget already has a different decision");
            }
            return;
        }
        jdbc.sql(
                        """
                        UPDATE budgets
                        SET decision = ?, decision_source = ?, decision_reason = ?, decided_at = CURRENT_TIMESTAMP
                        WHERE request_id = ?
                        """)
                .params(request.decision().name(), request.source(), request.reason(), request.budgetId())
                .update();
        if (request.decision() == BudgetMessages.Decision.APPROVED) {
            jdbc.sql("INSERT INTO payments(budget_id, status) VALUES (?, 'PENDING_CREATION')")
                    .param(request.budgetId())
                    .update();
        }
        var result = new BudgetMessages.Decided(request.budgetId(), request.orderId(), request.decision());
        jdbc.sql("INSERT INTO billing_outbox(event_id, routing_key, payload) VALUES (?, ?, ?)")
                .params(UUID.randomUUID(), BudgetMessages.DECISION_RESULTS, json.writeValueAsString(result))
                .update();
    }

    private record RecordedDecision(String value) {}
}
