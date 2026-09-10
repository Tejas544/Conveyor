package com.conveyor.contracts.events;

/**
 * ARCHITECTURE.md §6.3 — one line item inside {@link ReserveInventoryPayload#items()} or {@link
 * InventoryReservedPayload#items()}. Unlike {@link OrderItemPayload}, no price: inventory doesn't
 * own pricing.
 */
public record InventoryItemPayload(String sku, int quantity) {}
