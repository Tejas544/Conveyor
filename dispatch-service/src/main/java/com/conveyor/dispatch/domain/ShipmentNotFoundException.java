package com.conveyor.dispatch.domain;

import java.util.UUID;

/** ARCHITECTURE.md §10.4: {@code GET /shipments/{orderId}} for an order with no shipment yet. */
public class ShipmentNotFoundException extends RuntimeException {

  private ShipmentNotFoundException(String message) {
    super(message);
  }

  public static ShipmentNotFoundException forOrderId(UUID orderId) {
    return new ShipmentNotFoundException("No shipment found for order: " + orderId);
  }
}
