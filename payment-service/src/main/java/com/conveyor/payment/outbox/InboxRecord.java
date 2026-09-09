package com.conveyor.payment.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

/**
 * ARCHITECTURE.md §5.6, §9. A primary-key violation on {@link InboxRecordId} means "already
 * processed" — the consumer's idempotency guard.
 */
@Entity
@Table(name = "inbox")
public class InboxRecord {

  @EmbeddedId private InboxRecordId id;

  @CreationTimestamp
  @Column(name = "processed_at", nullable = false, updatable = false)
  private java.time.Instant processedAt;

  protected InboxRecord() {}

  public InboxRecord(InboxRecordId id) {
    this.id = id;
  }

  public InboxRecordId getId() {
    return id;
  }

  public java.time.Instant getProcessedAt() {
    return processedAt;
  }
}
