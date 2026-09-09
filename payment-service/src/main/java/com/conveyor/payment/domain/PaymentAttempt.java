package com.conveyor.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * ARCHITECTURE.md §5.4. Idempotency evidence keyed on {@code sagaId:CHARGE_PAYMENT}; a replayed
 * command finds this row and re-emits the original reply instead of charging twice.
 */
@Entity
@Table(name = "payment_attempts")
public class PaymentAttempt {

  @Id private UUID id;

  @Column(name = "order_id", nullable = false)
  private UUID orderId;

  @Column(name = "idempotency_key", nullable = false, unique = true)
  private String idempotencyKey;

  @Column(nullable = false)
  private String outcome;

  @Column(name = "gateway_reference")
  private String gatewayReference;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected PaymentAttempt() {}

  public PaymentAttempt(
      UUID id, UUID orderId, String idempotencyKey, String outcome, String gatewayReference) {
    this.id = id;
    this.orderId = orderId;
    this.idempotencyKey = idempotencyKey;
    this.outcome = outcome;
    this.gatewayReference = gatewayReference;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public String getOutcome() {
    return outcome;
  }

  public String getGatewayReference() {
    return gatewayReference;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
