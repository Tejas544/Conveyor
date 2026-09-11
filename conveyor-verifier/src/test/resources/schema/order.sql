-- order-service baseline schema. ARCHITECTURE.md §5.1, §5.6.
-- Owns: orders, order_items, users, outbox, inbox. No other service reads
-- these tables directly (ARCHITECTURE.md §4).

create table orders (
  id                uuid primary key,
  customer_id       uuid           not null,
  status            text           not null,
  saga_id           uuid,
  total_amount      numeric(12,2)  not null,
  currency          char(3)        not null,
  shipping_address  jsonb          not null,
  idempotency_key   text unique,
  version           bigint         not null default 0,
  created_at        timestamptz    not null default now(),
  updated_at        timestamptz    not null default now()
);

create index idx_orders_status on orders (status);
create index idx_orders_saga_id on orders (saga_id);
create index idx_orders_customer_id on orders (customer_id);

create table order_items (
  id          uuid primary key,
  order_id    uuid not null references orders (id) on delete cascade,
  sku         text not null,
  quantity    int  not null check (quantity > 0),
  unit_price  numeric(12,2) not null,
  unique (order_id, sku)
);

create table users (
  id            uuid primary key,
  username      text unique not null,
  password_hash text not null,
  roles         text[] not null
);

-- Shared infrastructure tables (ARCHITECTURE.md §5.6) — one copy per service
-- database, never shared across services.
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
-- Phase 6: OrderPlaced never carried paymentMethodToken even though POST /orders
-- always required it (ARCHITECTURE.md §10.1) and ChargePayment needs it
-- (§6.3) — a gap in the frozen spec's V1 baseline, closed here rather than
-- carried forward silently. See CONTEXT.md's Key Decisions Log.
alter table orders add column payment_method_token text;
