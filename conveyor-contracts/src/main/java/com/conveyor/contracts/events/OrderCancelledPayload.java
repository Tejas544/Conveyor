package com.conveyor.contracts.events;

import java.util.List;

/**
 * ARCHITECTURE.md §6.3, §8.2 — the {@code payload} of an {@code OrderCancelled} event. {@code
 * compensatedSteps} names the forward steps that were undone (e.g. {@code ["RESERVE_INVENTORY"]}),
 * derived from the saga's own step log rather than a hardcoded list (ARCHITECTURE.md §3, ADR-1).
 */
public record OrderCancelledPayload(String reason, List<String> compensatedSteps) {

  public static final String EVENT_TYPE = "OrderCancelled";
  public static final int SCHEMA_VERSION = 1;

  public static final String REASON_INVENTORY_RESERVATION_FAILED = "INVENTORY_RESERVATION_FAILED";
  public static final String REASON_RESERVE_INVENTORY_TIMEOUT = "RESERVE_INVENTORY_TIMEOUT";
  public static final String REASON_PAYMENT_DECLINED = "PAYMENT_DECLINED";
  public static final String REASON_CHARGE_PAYMENT_TIMEOUT = "CHARGE_PAYMENT_TIMEOUT";
  public static final String REASON_ABORTED_BY_OPERATOR = "ABORTED_BY_OPERATOR";
}
