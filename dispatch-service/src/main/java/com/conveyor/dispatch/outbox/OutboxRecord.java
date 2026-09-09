package com.conveyor.dispatch.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * ARCHITECTURE.md §5.6, ADR-7. Written in the same local transaction as the business change it
 * announces; a poller publishes rows and stamps {@link #publishedAt}.
 */
@Entity
@Table(name = "outbox")
public class OutboxRecord {

  @Id private UUID id;

  @Column(name = "aggregate_type", nullable = false)
  private String aggregateType;

  @Column(name = "aggregate_id", nullable = false)
  private String aggregateId;

  @Column(name = "event_type", nullable = false)
  private String eventType;

  @Column(nullable = false)
  private String topic;

  @Column(name = "message_key", nullable = false)
  private String messageKey;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false)
  private Map<String, Object> payload;

  @JdbcTypeCode(SqlTypes.JSON)
  private Map<String, Object> headers;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "published_at")
  private Instant publishedAt;

  @Column(nullable = false)
  private int attempts;

  protected OutboxRecord() {}

  public OutboxRecord(
      UUID id,
      String aggregateType,
      String aggregateId,
      String eventType,
      String topic,
      String messageKey,
      Map<String, Object> payload,
      Map<String, Object> headers) {
    this.id = id;
    this.aggregateType = aggregateType;
    this.aggregateId = aggregateId;
    this.eventType = eventType;
    this.topic = topic;
    this.messageKey = messageKey;
    this.payload = payload;
    this.headers = headers;
  }

  public UUID getId() {
    return id;
  }

  public String getAggregateType() {
    return aggregateType;
  }

  public String getAggregateId() {
    return aggregateId;
  }

  public String getEventType() {
    return eventType;
  }

  public String getTopic() {
    return topic;
  }

  public String getMessageKey() {
    return messageKey;
  }

  public Map<String, Object> getPayload() {
    return payload;
  }

  public Map<String, Object> getHeaders() {
    return headers;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getPublishedAt() {
    return publishedAt;
  }

  public void markPublished(Instant when) {
    this.publishedAt = when;
  }

  public int getAttempts() {
    return attempts;
  }

  public void incrementAttempts() {
    this.attempts++;
  }
}
