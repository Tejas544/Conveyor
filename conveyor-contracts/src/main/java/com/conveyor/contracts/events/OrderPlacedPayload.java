package com.conveyor.contracts.events;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * ARCHITECTURE.md §6.3 — the {@code payload} of an {@code OrderPlaced} envelope. {@code
 * schemaVersion} 1; see {@code schemas/order-placed.schema.json}.
 */
public record OrderPlacedPayload(
    UUID customerId,
    List<OrderItemPayload> items,
    BigDecimal totalAmount,
    String currency,
    ShippingAddressPayload shippingAddress,
    String paymentMethodToken) {

  public static final String EVENT_TYPE = "OrderPlaced";
  public static final int SCHEMA_VERSION = 1;
}
