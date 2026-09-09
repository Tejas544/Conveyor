package com.conveyor.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/** ARCHITECTURE.md §5.4. One payment per order — {@code order_id} is unique. */
@Entity
@Table(name = "payments")
public class Payment {

  @Id private UUID id;

  @Column(name = "order_id", nullable = false, unique = true)
  private UUID orderId;

  @Column(nullable = false)
  private BigDecimal amount;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(nullable = false, length = 3)
  private String currency;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private PaymentStatus status;

  @Column(name = "gateway_reference")
  private String gatewayReference;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Payment() {}

  public Payment(
      UUID id,
      UUID orderId,
      BigDecimal amount,
      String currency,
      PaymentStatus status,
      String gatewayReference) {
    this.id = id;
    this.orderId = orderId;
    this.amount = amount;
    this.currency = currency;
    this.status = status;
    this.gatewayReference = gatewayReference;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public String getCurrency() {
    return currency;
  }

  public PaymentStatus getStatus() {
    return status;
  }

  public void setStatus(PaymentStatus status) {
    this.status = status;
  }

  public String getGatewayReference() {
    return gatewayReference;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
