package com.conveyor.saga.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * ARCHITECTURE.md §5.2. Append-only — a step that starts and then succeeds writes two rows, never
 * an update. This is the per-order timeline the dashboard renders and the chaos analysis replays.
 */
@Entity
@Table(name = "saga_steps")
public class SagaStep {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "saga_id", nullable = false)
  private UUID sagaId;

  @Column(nullable = false)
  private int seq;

  @Column(nullable = false)
  private String step;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private StepDirection direction;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private StepStatus status;

  @Column(name = "correlation_id")
  private UUID correlationId;

  @JdbcTypeCode(SqlTypes.JSON)
  private Map<String, Object> detail;

  @CreationTimestamp
  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;

  protected SagaStep() {}

  public SagaStep(
      UUID sagaId,
      int seq,
      String step,
      StepDirection direction,
      StepStatus status,
      UUID correlationId,
      Map<String, Object> detail) {
    this.sagaId = sagaId;
    this.seq = seq;
    this.step = step;
    this.direction = direction;
    this.status = status;
    this.correlationId = correlationId;
    this.detail = detail;
  }

  public Long getId() {
    return id;
  }

  public UUID getSagaId() {
    return sagaId;
  }

  public int getSeq() {
    return seq;
  }

  public String getStep() {
    return step;
  }

  public StepDirection getDirection() {
    return direction;
  }

  public StepStatus getStatus() {
    return status;
  }

  public UUID getCorrelationId() {
    return correlationId;
  }

  public Map<String, Object> getDetail() {
    return detail;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }
}
