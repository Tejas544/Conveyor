package com.conveyor.inventory.web.dto;

import com.conveyor.inventory.domain.Reservation;
import com.conveyor.inventory.domain.ReservationStatus;
import java.time.Instant;
import java.util.UUID;

/** ARCHITECTURE.md §10.3: {@code GET /inventory/{sku}/reservations} — who is holding this stock. */
public record ReservationResponse(
    UUID id,
    UUID orderId,
    String sku,
    int quantity,
    ReservationStatus status,
    Instant createdAt,
    Instant releasedAt) {

  public static ReservationResponse from(Reservation reservation) {
    return new ReservationResponse(
        reservation.getId(),
        reservation.getOrderId(),
        reservation.getSku(),
        reservation.getQuantity(),
        reservation.getStatus(),
        reservation.getCreatedAt(),
        reservation.getReleasedAt());
  }
}
