ALTER TABLE budgets ADD COLUMN decision VARCHAR(8) CHECK (decision = 'APPROVED' OR decision = 'REJECTED');
ALTER TABLE budgets ADD COLUMN decision_source TEXT;
ALTER TABLE budgets ADD COLUMN decision_reason TEXT;
ALTER TABLE budgets ADD COLUMN decided_at TIMESTAMP WITH TIME ZONE;

CREATE TABLE billing_outbox (
    event_id UUID PRIMARY KEY,
    routing_key VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX billing_outbox_pending ON billing_outbox (published_at, created_at);
