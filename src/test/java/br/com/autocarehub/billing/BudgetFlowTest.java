package br.com.autocarehub.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import tools.jackson.databind.json.JsonMapper;

class BudgetFlowTest {
    private EmbeddedDatabase db;
    private JdbcClient jdbc;
    private final JsonMapper json = JsonMapper.builder().build();
    private final UUID orderId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();
    private final BudgetMessages.Item part =
            new BudgetMessages.Item(UUID.randomUUID(), "Filtro", 2, new BigDecimal("19.90"));

    @BeforeEach
    void setup() {
        db = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName(UUID.randomUUID() + ";MODE=PostgreSQL")
                .addScripts(
                        "db/migration/V1__create_budgets.sql",
                        "db/migration/V2__budget_decisions.sql",
                        "db/migration/V3__payments.sql")
                .build();
        jdbc = JdbcClient.create(db);
    }

    @AfterEach
    void close() {
        db.shutdown();
    }

    @Test
    void persistsExactTotalAndReusesRequestId() throws Exception {
        var consumer = new BudgetConsumer(jdbc, json);
        var labor = new BudgetMessages.Item(UUID.randomUUID(), "Mão de obra", 1, new BigDecimal("60.20"));
        var request = new BudgetMessages.Request(requestId, orderId, List.of(part, labor));
        String payload = json.writeValueAsString(request);
        consumer.generate(payload);
        consumer.generate(payload);

        assertThat(jdbc.sql("SELECT total_amount FROM budgets")
                        .query(BigDecimal.class)
                        .list())
                .containsExactly(new BigDecimal("100.00"));

        var rabbit = mock(RabbitTemplate.class);
        doAnswer(call -> {
                    var result = json.readValue((String) call.getArgument(2), BudgetMessages.Result.class);
                    assertThat(result)
                            .isEqualTo(new BudgetMessages.Result(requestId, orderId, new BigDecimal("100.00")));
                    MessagePostProcessor processor = call.getArgument(3);
                    assertThat(processor
                                    .postProcessMessage(new Message(new byte[0]))
                                    .getMessageProperties()
                                    .getDeliveryMode())
                            .isEqualTo(MessageDeliveryMode.PERSISTENT);
                    CorrelationData confirmation = call.getArgument(4);
                    confirmation.getFuture().complete(new CorrelationData.Confirm(true, null));
                    return null;
                })
                .when(rabbit)
                .convertAndSend(
                        eq(""),
                        eq(BudgetMessages.RESULTS),
                        any(String.class),
                        any(MessagePostProcessor.class),
                        any(CorrelationData.class));

        new BudgetPublisher(jdbc, rabbit).publish();
        assertThat(published()).isTrue();
    }

    @Test
    void leavesUnconfirmedResultForRetry() {
        new BudgetConsumer(jdbc, json)
                .generate(json.writeValueAsString(new BudgetMessages.Request(requestId, orderId, List.of(part))));
        var rabbit = mock(RabbitTemplate.class);
        doAnswer(call -> {
                    CorrelationData confirmation = call.getArgument(4);
                    confirmation.getFuture().complete(new CorrelationData.Confirm(false, "rejected"));
                    return null;
                })
                .when(rabbit)
                .convertAndSend(
                        eq(""),
                        eq(BudgetMessages.RESULTS),
                        any(String.class),
                        any(MessagePostProcessor.class),
                        any(CorrelationData.class));

        assertThatThrownBy(() -> new BudgetPublisher(jdbc, rabbit).publish()).isInstanceOf(IllegalStateException.class);
        assertThat(published()).isFalse();
    }

    @Test
    void skipsEmptyOutboxAndDeclaresDeadLetterQueues() throws Exception {
        var rabbit = mock(RabbitTemplate.class);
        new BudgetPublisher(jdbc, rabbit).publish();
        verifyNoInteractions(rabbit);
        assertThat(new BudgetQueues().billingBudgetQueues().getDeclarablesByType(Queue.class))
                .extracting(Queue::getName)
                .containsExactlyInAnyOrder(
                        BudgetMessages.REQUESTS,
                        BudgetMessages.REQUESTS + ".dead",
                        BudgetMessages.DECISIONS,
                        BudgetMessages.DECISIONS + ".dead");
    }

    @Test
    void validatesItemAndTotalBeforePersistence() {
        assertThatThrownBy(() -> new BudgetMessages.Item(UUID.randomUUID(), "Filtro", 0, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BudgetMessages.Request(requestId, orderId, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        var expensive = new BudgetMessages.Item(UUID.randomUUID(), "Peça", 10, new BigDecimal("9999999999999.99"));
        assertThatThrownBy(() -> new BudgetMessages.Request(requestId, orderId, List.of(expensive)).totalAmount())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordsOneDecisionAndPublishesOneEvent() throws Exception {
        var consumer = new BudgetConsumer(jdbc, json);
        consumer.generate(json.writeValueAsString(new BudgetMessages.Request(requestId, orderId, List.of(part))));
        var decision = new BudgetMessages.Decide(requestId, orderId, BudgetMessages.Decision.APPROVED, "api", null);
        String payload = json.writeValueAsString(decision);
        consumer.decide(payload);
        consumer.decide(payload);
        assertThatThrownBy(() -> consumer.decide(json.writeValueAsString(
                        new BudgetMessages.Decide(requestId, orderId, BudgetMessages.Decision.REJECTED, "api", null))))
                .isInstanceOf(IllegalStateException.class);

        var controller = new BudgetQueryController(jdbc, "internal");
        assertThat(controller.get(requestId, "internal").decision()).isEqualTo("APPROVED");
        assertThatThrownBy(() -> controller.get(requestId, "invalid")).hasMessageContaining("401");
        assertThatThrownBy(() -> controller.get(UUID.randomUUID(), "internal")).hasMessageContaining("404");
        assertThatThrownBy(() -> new BudgetQueryController(jdbc, "").get(requestId, ""))
                .hasMessageContaining("401");

        var rabbit = mock(RabbitTemplate.class);
        doAnswer(call -> {
                    var result = json.readValue((String) call.getArgument(2), BudgetMessages.Decided.class);
                    assertThat(result)
                            .isEqualTo(
                                    new BudgetMessages.Decided(requestId, orderId, BudgetMessages.Decision.APPROVED));
                    CorrelationData confirmation = call.getArgument(4);
                    confirmation.getFuture().complete(new CorrelationData.Confirm(true, null));
                    return null;
                })
                .when(rabbit)
                .convertAndSend(
                        eq(""),
                        eq(BudgetMessages.DECISION_RESULTS),
                        any(String.class),
                        any(MessagePostProcessor.class),
                        any(CorrelationData.class));
        var publisher = new BudgetPublisher(jdbc, rabbit);
        publisher.publishEvents();
        publisher.publishEvents();
        assertThat(jdbc.sql("SELECT published_at IS NOT NULL FROM billing_outbox")
                        .query(Boolean.class)
                        .list())
                .containsExactly(true);
    }

    private boolean published() {
        return jdbc.sql("SELECT published_at IS NOT NULL FROM budgets")
                .query(Boolean.class)
                .single();
    }
}
