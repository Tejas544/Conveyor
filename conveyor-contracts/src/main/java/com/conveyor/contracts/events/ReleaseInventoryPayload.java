package com.conveyor.contracts.events;

import java.util.List;
import java.util.UUID;

/** ARCHITECTURE.md §6.3 — the {@code payload} of a {@code ReleaseInventory} command. */
public record ReleaseInventoryPayload(List<UUID> reservationIds) {

  public static final String EVENT_TYPE = "ReleaseInventory";
  public static final int SCHEMA_VERSION = 1;
}
