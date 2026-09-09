-- dispatch-service baseline schema. ARCHITECTURE.md §5.5, §5.6.
-- Owns: shipments, outbox, inbox (Postgres) and notifications (MongoDB,
-- initialized separately by NotificationCollectionInitializer).
--
-- Note (CONTEXT.md decisions log): ARCHITECTURE.md §4's ownership table lists
-- dispatch-service's Postgres tables as "shipments, inbox" without outbox,
-- but ADR-7 ("no service ever writes its database and publishes to Kafka as
-- two independent operations") and PLAN.md Phase 2's deliverable
-- ("outbox/inbox tables in all five service databases") both require one
-- here too, since dispatch-service publishes ShipmentCreated. Treated as a
-- §4 table omission, not a deliberate exception.

create table shipments (
  id              uuid primary key,
  order_id        uuid not null unique,
  carrier         text not null,
  tracking_number text not null,
  status          text not null,
  created_at      timestamptz not null default now()
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
