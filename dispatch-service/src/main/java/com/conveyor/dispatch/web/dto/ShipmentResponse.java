package com.conveyor.dispatch.web.dto;

import com.conveyor.dispatch.domain.Shipment;
import java.time.Instant;
import java.util.UUID;

/** ARCHITECTURE.md §10.4: {@code GET /shipments/{orderId}}. */
public record ShipmentResponse(
    UUID id,
    UUID orderId,
    String carrier,
    String trackingNumber,
    String status,
    Instant createdAt) {

  public static ShipmentResponse from(Shipment shipment) {
    return new ShipmentResponse(
        shipment.getId(),
        shipment.getOrderId(),
        shipment.getCarrier(),
        shipment.getTrackingNumber(),
        shipment.getStatus(),
        shipment.getCreatedAt());
  }
}
