CREATE TABLE payments (
    budget_id UUID PRIMARY KEY REFERENCES budgets(request_id),
    provider_order_id VARCHAR(128) UNIQUE,
    qr_data TEXT,
    status VARCHAR(24) NOT NULL CHECK (
        status = 'PENDING_CREATION' OR status = 'AWAITING_PAYMENT' OR status = 'CONFIRMED'
        OR status = 'EXPIRED' OR status = 'CANCELED' OR status = 'REFUNDED'),
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX payments_to_create ON payments (status, next_attempt_at);
