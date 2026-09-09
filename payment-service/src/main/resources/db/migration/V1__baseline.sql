-- payment-service baseline schema. ARCHITECTURE.md §5.4, §5.6.
-- Owns: payments, payment_attempts, outbox, inbox.

create table payments (
  id                uuid primary key,
  order_id          uuid not null unique,
  amount            numeric(12,2) not null,
  currency          char(3) not null,
  status            text not null,
  gateway_reference text,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now()
);

create index idx_payments_status on payments (status);

-- Idempotency evidence (ARCHITECTURE.md §5.4): idempotency_key =
-- "sagaId:CHARGE_PAYMENT". A replayed command finds this row and re-emits
-- the original reply instead of charging twice.
create table payment_attempts (
  id                uuid primary key,
  order_id          uuid not null,
  idempotency_key   text unique not null,
  outcome           text not null,
  gateway_reference text,
  created_at        timestamptz not null default now()
);

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
