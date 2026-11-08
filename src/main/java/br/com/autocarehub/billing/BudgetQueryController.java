package br.com.autocarehub.billing;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class BudgetQueryController {
    private final JdbcClient jdbc;
    private final byte[] internalToken;

    BudgetQueryController(JdbcClient jdbc, @Value("${BILLING_INTERNAL_TOKEN:}") String token) {
        this.jdbc = jdbc;
        this.internalToken = token.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/internal/v1/budgets/{budgetId}")
    BudgetView get(@PathVariable UUID budgetId, @RequestHeader("X-Internal-Service-Token") String token) {
        if (internalToken.length == 0
                || !MessageDigest.isEqual(internalToken, token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return jdbc.sql(
                        """
                SELECT b.order_id, b.total_amount, b.decision, CASE WHEN p.status = 'CONFIRMED' AND p.refund_review_required THEN 'REFUND_REVIEW_REQUIRED'
                            WHEN p.status = 'CONFIRMED' AND p.refund_requested_at IS NOT NULL THEN 'REFUND_PENDING'
                            WHEN p.creation_review_required THEN 'REVIEW_REQUIRED' ELSE p.status END,
                       p.qr_data
                FROM budgets b LEFT JOIN payments p ON p.budget_id = b.request_id WHERE b.request_id = ? AND b.generation_failure IS NULL
                """)
                .param(budgetId)
                .query((rs, row) -> new BudgetView(
                        budgetId,
                        rs.getObject(1, UUID.class),
                        rs.getBigDecimal(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5)))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    record BudgetView(
            UUID budgetId,
            UUID orderId,
            BigDecimal totalAmount,
            String decision,
            String paymentStatus,
            String qrData) {}
}
