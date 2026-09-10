package com.conveyor.contracts.events;

/**
 * ARCHITECTURE.md §6.3 — one entry inside {@link InventoryReservationFailedPayload#shortfalls()}.
 */
public record ShortfallPayload(String sku, int requested, int available) {}
