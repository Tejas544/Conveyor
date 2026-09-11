-- saga-orchestrator baseline schema. ARCHITECTURE.md §5.2, §5.6.
-- Owns: saga_instances, saga_steps, outbox, inbox — the coordinator's
-- transaction record. Single-writer rule: no other service writes these.

create table saga_instances (
  id             uuid primary key,
  order_id       uuid not null unique,
  definition     text not null,
  state          text not null,
  current_step   text,
  compensating   boolean not null default false,
  failure_reason text,
  deadline_at    timestamptz,
  attempt        int not null default 0,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now()
);

create index idx_saga_instances_state on saga_instances (state);
create index idx_saga_instances_deadline on saga_instances (deadline_at) where deadline_at is not null;

-- saga_steps is append-only (ARCHITECTURE.md §5.2): a step that starts and
-- then succeeds writes two rows. Never updated after insert.
create table saga_steps (
  id             bigserial primary key,
  saga_id        uuid not null references saga_instances (id) on delete cascade,
  seq            int  not null,
  step           text not null,
  direction      text not null,
  status         text not null,
  correlation_id uuid,
  detail         jsonb,
  occurred_at    timestamptz not null default now(),
  unique (saga_id, seq)
);

create index idx_saga_steps_saga_id on saga_steps (saga_id);

create table outbox (
  id            uuid primary key,
  aggregate_type text not null,
  aggregate_id   text not null,
  event_type     text not null,
  topic          text not null,
  message_key    text not null,
  payload        jsonb not null,
  headers        jsonb,
  created_at     timestamptz not null default now(),
  published_at   timestamptz,
  attempts       int not null default 0
);

create index idx_outbox_unpublished on outbox (created_at) where published_at is null;

create table inbox (
  message_id    uuid not null,
  consumer      text not null,
  processed_at  timestamptz not null default now(),
  primary key (message_id, consumer)
);
-- Phase 6: the orchestrator needs the order's amount/currency/payment method at
-- CHARGE_PAYMENT time, which arrives once (in OrderPlaced) well before that step
-- runs. These are saga-level attributes fixed at saga start, not per-step detail
-- (ARCHITECTURE.md §5.2's baseline table predates Phase 6 and omitted them).
alter table saga_instances
  add column total_amount numeric(12, 2),
  add column currency text,
  add column payment_method_token text;
