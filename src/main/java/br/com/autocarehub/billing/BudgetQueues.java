package br.com.autocarehub.billing;

import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class BudgetQueues {
    @Bean
    Declarables billingBudgetQueues() {
        return new Declarables(
                withDeadLetter(BudgetMessages.REQUESTS),
                QueueBuilder.durable(BudgetMessages.REQUESTS + ".dead").build(),
                withDeadLetter(BudgetMessages.DECISIONS),
                QueueBuilder.durable(BudgetMessages.DECISIONS + ".dead").build(),
                withDeadLetter(PaymentWorkflow.REPAIR_REFUNDS),
                QueueBuilder.durable(PaymentWorkflow.REPAIR_REFUNDS + ".dead").build());
    }

    private org.springframework.amqp.core.Queue withDeadLetter(String name) {
        return QueueBuilder.durable(name)
                .deadLetterExchange("")
                .deadLetterRoutingKey(name + ".dead")
                .build();
    }
}
