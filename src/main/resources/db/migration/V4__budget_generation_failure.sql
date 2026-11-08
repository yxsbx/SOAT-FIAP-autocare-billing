ALTER TABLE budgets ALTER COLUMN total_amount DROP NOT NULL;
ALTER TABLE budgets ADD COLUMN generation_failure VARCHAR(32);
ALTER TABLE budgets ADD CONSTRAINT budgets_generation_outcome CHECK (
    (total_amount IS NOT NULL AND generation_failure IS NULL)
    OR (total_amount IS NULL AND generation_failure IS NOT NULL)
);
