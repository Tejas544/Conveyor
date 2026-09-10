package com.conveyor.contracts.events;

import java.time.Instant;
import java.util.UUID;

/** ARCHITECTURE.md §6.3 — the {@code payload} of an {@code OrderConfirmed} event. */
public record OrderConfirmedPayload(UUID orderId, Instant confirmedAt) {

  public static final String EVENT_TYPE = "OrderConfirmed";
  public static final int SCHEMA_VERSION = 1;
}
