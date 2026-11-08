package br.com.autocarehub.billing;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class BudgetMessages {
    static final String REQUESTS = "autocare.billing.generate-budget.v1";
    static final String DECISIONS = "autocare.billing.decide-budget.v1";
    static final String DECISION_RESULTS = "autocare.os.budget-decided.v1";
    static final String RESULTS = "autocare.os.budget-generated.v1";

    record Request(UUID requestId, UUID orderId, List<Item> items) {
        Request {
            Objects.requireNonNull(requestId);
            Objects.requireNonNull(orderId);
            items = List.copyOf(items);
            if (items.isEmpty()) {
                throw new IllegalArgumentException("Budget requires at least one item");
            }
        }

        BigDecimal totalAmount() {
            var total = items.stream()
                    .map(item -> item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2);
            if (total.precision() > 15) {
                throw new TotalLimitExceeded();
            }
            return total;
        }
    }

    record Item(UUID itemId, String description, int quantity, BigDecimal unitPrice) {
        Item {
            Objects.requireNonNull(itemId);
            Objects.requireNonNull(description);
            if (description.isBlank() || quantity <= 0 || unitPrice.signum() < 0 || unitPrice.scale() > 2) {
                throw new IllegalArgumentException("Invalid budget item");
            }
        }
    }

    enum Decision {
        APPROVED,
        REJECTED
    }

    record Decide(UUID budgetId, UUID orderId, Decision decision, String source, String reason) {
        Decide {
            Objects.requireNonNull(budgetId);
            Objects.requireNonNull(orderId);
            Objects.requireNonNull(decision);
        }
    }

    record Decided(UUID budgetId, UUID orderId, Decision decision) {}

    static final class TotalLimitExceeded extends IllegalArgumentException {
        TotalLimitExceeded() {
            super("Budget total exceeds supported precision");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Result(UUID requestId, UUID orderId, BigDecimal totalAmount, String failure) {}
}
