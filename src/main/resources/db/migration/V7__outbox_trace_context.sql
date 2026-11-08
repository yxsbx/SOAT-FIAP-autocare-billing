ALTER TABLE budgets ADD COLUMN trace_context TEXT;
ALTER TABLE billing_outbox ADD COLUMN trace_context TEXT;
ALTER TABLE payments ADD COLUMN trace_context TEXT;
