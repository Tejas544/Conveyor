package com.conveyor.contracts.events;

import java.math.BigDecimal;

/** ARCHITECTURE.md §6.3 — one line item inside {@link OrderPlacedPayload#items()}. */
public record OrderItemPayload(String sku, int quantity, BigDecimal unitPrice) {}
