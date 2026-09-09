package com.conveyor.dispatch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/** ARCHITECTURE.md §5.5. One shipment per order — {@code order_id} is unique. */
@Entity
@Table(name = "shipments")
public class Shipment {

  @Id private UUID id;

  @Column(name = "order_id", nullable = false, unique = true)
  private UUID orderId;

  @Column(nullable = false)
  private String carrier;

  @Column(name = "tracking_number", nullable = false)
  private String trackingNumber;

  @Column(nullable = false)
  private String status;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected Shipment() {}

  public Shipment(UUID id, UUID orderId, String carrier, String trackingNumber, String status) {
    this.id = id;
    this.orderId = orderId;
    this.carrier = carrier;
    this.trackingNumber = trackingNumber;
    this.status = status;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public String getCarrier() {
    return carrier;
  }

  public String getTrackingNumber() {
    return trackingNumber;
  }

  public String getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
