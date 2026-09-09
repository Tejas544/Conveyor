package com.conveyor.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/** ARCHITECTURE.md §5.3. {@code (order_id, sku)} is unique — the idempotent-reservation key. */
@Entity
@Table(name = "reservations")
public class Reservation {

  @Id private UUID id;

  @Column(name = "order_id", nullable = false)
  private UUID orderId;

  @Column(nullable = false)
  private String sku;

  @Column(nullable = false)
  private int quantity;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private ReservationStatus status;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "released_at")
  private Instant releasedAt;

  protected Reservation() {}

  public Reservation(UUID id, UUID orderId, String sku, int quantity, ReservationStatus status) {
    this.id = id;
    this.orderId = orderId;
    this.sku = sku;
    this.quantity = quantity;
    this.status = status;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public String getSku() {
    return sku;
  }

  public int getQuantity() {
    return quantity;
  }

  public ReservationStatus getStatus() {
    return status;
  }

  public void setStatus(ReservationStatus status) {
    this.status = status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getReleasedAt() {
    return releasedAt;
  }

  public void setReleasedAt(Instant releasedAt) {
    this.releasedAt = releasedAt;
  }
}
