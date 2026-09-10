package com.conveyor.contracts.events;

import java.util.List;

/** ARCHITECTURE.md §6.3 — the {@code payload} of a {@code ReserveInventory} command. */
public record ReserveInventoryPayload(List<InventoryItemPayload> items) {

  public static final String EVENT_TYPE = "ReserveInventory";
  public static final int SCHEMA_VERSION = 1;
}
