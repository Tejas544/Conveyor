package com.conveyor.contracts.events;

import java.util.List;
import java.util.UUID;

/** ARCHITECTURE.md §6.3 — the {@code payload} of an {@code InventoryReserved} reply. */
public record InventoryReservedPayload(
    List<UUID> reservationIds, List<InventoryItemPayload> items) {

  public static final String EVENT_TYPE = "InventoryReserved";
  public static final int SCHEMA_VERSION = 1;
}
