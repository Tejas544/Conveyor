package com.conveyor.order.domain;

/** ARCHITECTURE.md §7.1 — the customer-facing projection of saga progress. */
public enum OrderStatus {
  PLACED,
  INVENTORY_RESERVED,
  PAYMENT_CHARGED,
  CONFIRMED,
  COMPENSATING,
  CANCELLED
}
