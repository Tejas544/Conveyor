package com.conveyor.order.domain;

import java.util.Map;
import java.util.Set;

/**
 * ARCHITECTURE.md §7.1 — the customer-facing projection of saga progress. {@link #canTransitionTo}
 * is the guard: {@code CONFIRMED} and {@code CANCELLED} are terminal, and every other move not
 * drawn on the state diagram is rejected rather than silently written.
 *
 * <p>{@code CANCELLED} is reachable directly from {@code PLACED}/{@code INVENTORY_RESERVED}/{@code
 * PAYMENT_CHARGED}, not only via {@code COMPENSATING} — BUG-0026. A saga abort triggered by a
 * reply event ({@code InventoryReservationFailed}/{@code PaymentFailed}) naturally produces the
 * {@code COMPENSATING} intermediate step for this projection, but {@code
 * SagaTimeoutSweeper}'s deadline-driven aborts never received a reply to relay in the first place —
 * they go straight from internal saga state to publishing {@code OrderCancelled}, with no
 * intermediate event for this listener to project. Before this fix, that direct {@code
 * OrderCancelled} was rejected as an illegal transition and silently discarded (logged, not
 * thrown, per {@code SagaEventProjectionListener}'s "saga and projection disagree" branch),
 * leaving the order permanently stuck reporting an in-progress status while the saga itself had
 * already correctly reached {@code ABORTED} — found live via Phase 11's chaos matrix, reproduced
 * 100% of the time for every timeout-triggered compensation path.
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
          PLACED, Set.of(INVENTORY_RESERVED, COMPENSATING, CANCELLED),
          INVENTORY_RESERVED, Set.of(PAYMENT_CHARGED, COMPENSATING, CANCELLED),
          PAYMENT_CHARGED, Set.of(CONFIRMED, COMPENSATING, CANCELLED),
          CONFIRMED, Set.of(),
          COMPENSATING, Set.of(CANCELLED),
          CANCELLED, Set.of());

  public boolean canTransitionTo(OrderStatus target) {
    return ALLOWED_TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
  }
}
