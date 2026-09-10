package com.conveyor.contracts.events;

import java.util.UUID;

/** ARCHITECTURE.md §6.3 — the {@code payload} of a {@code ShipmentCreated} event. */
public record ShipmentCreatedPayload(UUID shipmentId, String carrier, String trackingNumber) {

  public static final String EVENT_TYPE = "ShipmentCreated";
  public static final int SCHEMA_VERSION = 1;
}
