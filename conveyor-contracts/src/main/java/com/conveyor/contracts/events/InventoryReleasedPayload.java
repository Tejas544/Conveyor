package com.conveyor.contracts.events;

import java.util.List;
import java.util.UUID;

/** ARCHITECTURE.md §6.3 — the {@code payload} of an {@code InventoryReleased} reply. */
public record InventoryReleasedPayload(List<UUID> reservationIds) {

  public static final String EVENT_TYPE = "InventoryReleased";
  public static final int SCHEMA_VERSION = 1;
}
