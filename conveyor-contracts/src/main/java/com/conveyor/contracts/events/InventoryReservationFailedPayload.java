package com.conveyor.contracts.events;

import java.util.List;

/** ARCHITECTURE.md §6.3 — the {@code payload} of an {@code InventoryReservationFailed} reply. */
public record InventoryReservationFailedPayload(String reason, List<ShortfallPayload> shortfalls) {

  public static final String EVENT_TYPE = "InventoryReservationFailed";
  public static final int SCHEMA_VERSION = 1;

  public static final String REASON_INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK";
  public static final String REASON_UNKNOWN_SKU = "UNKNOWN_SKU";
}
