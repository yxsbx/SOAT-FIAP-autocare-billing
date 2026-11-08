ALTER TABLE payments ADD COLUMN refund_requested_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE payments ADD COLUMN refund_next_attempt_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE payments ADD COLUMN refund_submitted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE payments ADD COLUMN refund_review_required BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX payments_to_refund ON payments (status, refund_next_attempt_at);
