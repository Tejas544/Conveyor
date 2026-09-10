package com.conveyor.order.domain;

import java.util.Map;
import java.util.Set;

/**
 * ARCHITECTURE.md §7.1 — the customer-facing projection of saga progress. {@link #canTransitionTo}
 * is the guard: {@code CONFIRMED} and {@code CANCELLED} are terminal, and every other move not
 * drawn on the state diagram is rejected rather than silently written.
 */
public enum OrderStatus {
  PLACED,
  INVENTORY_RESERVED,
  PAYMENT_CHARGED,
  CONFIRMED,
  COMPENSATING,
  CANCELLED;

  private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED_TRANSITIONS =
      Map.of(
          PLACED, Set.of(INVENTORY_RESERVED, COMPENSATING),
          INVENTORY_RESERVED, Set.of(PAYMENT_CHARGED, COMPENSATING),
          PAYMENT_CHARGED, Set.of(CONFIRMED, COMPENSATING),
          CONFIRMED, Set.of(),
          COMPENSATING, Set.of(CANCELLED),
          CANCELLED, Set.of());

  public boolean canTransitionTo(OrderStatus target) {
    return ALLOWED_TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
  }
}
