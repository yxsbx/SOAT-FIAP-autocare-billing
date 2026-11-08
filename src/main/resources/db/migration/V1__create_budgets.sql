CREATE TABLE budgets (
    request_id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    items TEXT NOT NULL,
    total_amount NUMERIC(15, 2) NOT NULL CHECK (total_amount >= 0),
    response_payload TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX budgets_by_order ON budgets (order_id, created_at);
CREATE INDEX budgets_to_publish ON budgets (published_at, created_at);
