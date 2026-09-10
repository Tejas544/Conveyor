-- Phase 6: OrderPlaced never carried paymentMethodToken even though POST /orders
-- always required it (ARCHITECTURE.md §10.1) and ChargePayment needs it
-- (§6.3) — a gap in the frozen spec's V1 baseline, closed here rather than
-- carried forward silently. See CONTEXT.md's Key Decisions Log.
alter table orders add column payment_method_token text;
