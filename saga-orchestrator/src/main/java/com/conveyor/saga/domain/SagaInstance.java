package com.conveyor.saga.domain;

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
import org.hibernate.annotations.UpdateTimestamp;

/**
 * ARCHITECTURE.md §5.2 — the coordinator's transaction record, the direct analogue of Anvil's 2PC
 * transaction log. {@code order_id} is unique: one saga per order.
 */
@Entity
@Table(name = "saga_instances")
public class SagaInstance {

  @Id private UUID id;

  @Column(name = "order_id", nullable = false, unique = true)
  private UUID orderId;

  @Column(nullable = false)
  private String definition;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private SagaState state;

  @Column(name = "current_step")
  private String currentStep;

  @Column(nullable = false)
  private boolean compensating;

  @Column(name = "failure_reason")
  private String failureReason;

  @Column(name = "deadline_at")
  private Instant deadlineAt;

  @Column(nullable = false)
  private int attempt;

  @Column(name = "total_amount")
  private BigDecimal totalAmount;

  @Column private String currency;

  @Column(name = "payment_method_token")
  private String paymentMethodToken;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected SagaInstance() {}

  public SagaInstance(UUID id, UUID orderId, String definition, SagaState state) {
    this.id = id;
    this.orderId = orderId;
    this.definition = definition;
    this.state = state;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public String getDefinition() {
    return definition;
  }

  public SagaState getState() {
    return state;
  }

  public void setState(SagaState state) {
    this.state = state;
  }

  public String getCurrentStep() {
    return currentStep;
  }

  public void setCurrentStep(String currentStep) {
    this.currentStep = currentStep;
  }

  public boolean isCompensating() {
    return compensating;
  }

  public void setCompensating(boolean compensating) {
    this.compensating = compensating;
  }

  public String getFailureReason() {
    return failureReason;
  }

  public void setFailureReason(String failureReason) {
    this.failureReason = failureReason;
  }

  public Instant getDeadlineAt() {
    return deadlineAt;
  }

  public void setDeadlineAt(Instant deadlineAt) {
    this.deadlineAt = deadlineAt;
  }

  public int getAttempt() {
    return attempt;
  }

  public void setAttempt(int attempt) {
    this.attempt = attempt;
  }

  public BigDecimal getTotalAmount() {
    return totalAmount;
  }

  public void setTotalAmount(BigDecimal totalAmount) {
    this.totalAmount = totalAmount;
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }

  public String getPaymentMethodToken() {
    return paymentMethodToken;
  }

  public void setPaymentMethodToken(String paymentMethodToken) {
    this.paymentMethodToken = paymentMethodToken;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
