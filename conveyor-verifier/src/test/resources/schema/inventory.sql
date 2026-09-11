-- inventory-service baseline schema. ARCHITECTURE.md §5.3, §5.6, ADR-9.
-- Owns: stock_items, reservations, stock_adjustments, outbox, inbox (Postgres)
-- and catalog (MongoDB, initialized separately by CatalogCollectionInitializer).

create table stock_items (
  sku           text primary key,
  on_hand       int  not null check (on_hand >= 0),
  reserved      int  not null check (reserved >= 0),
  reorder_level int  not null default 10,
  version       bigint not null default 0,
  updated_at    timestamptz not null default now(),
  check (on_hand - reserved >= 0)
);

create table reservations (
  id          uuid primary key,
  order_id    uuid not null,
  sku         text not null,
  quantity    int  not null check (quantity > 0),
  status      text not null,
  created_at  timestamptz not null default now(),
  released_at timestamptz,
  unique (order_id, sku)
);

create index idx_reservations_sku on reservations (sku);
create index idx_reservations_status on reservations (status);

create table stock_adjustments (
  id         uuid primary key,
  sku        text not null,
  delta      int  not null,
  reason     text not null,
  actor      text not null,
  created_at timestamptz not null default now()
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
