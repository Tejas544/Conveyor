-- Phase 6: the orchestrator needs the order's amount/currency/payment method at
-- CHARGE_PAYMENT time, which arrives once (in OrderPlaced) well before that step
-- runs. These are saga-level attributes fixed at saga start, not per-step detail
-- (ARCHITECTURE.md §5.2's baseline table predates Phase 6 and omitted them).
alter table saga_instances
  add column total_amount numeric(12, 2),
  add column currency text,
  add column payment_method_token text;
