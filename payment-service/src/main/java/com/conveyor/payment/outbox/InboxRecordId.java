package com.conveyor.payment.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite key {@code (message_id, consumer)} — one row per consumer per message. */
@Embeddable
public class InboxRecordId implements Serializable {

  @Column(name = "message_id", nullable = false)
  private UUID messageId;

  @Column(nullable = false)
  private String consumer;

  protected InboxRecordId() {}

  public InboxRecordId(UUID messageId, String consumer) {
    this.messageId = messageId;
    this.consumer = consumer;
  }

  public UUID getMessageId() {
    return messageId;
  }

  public String getConsumer() {
    return consumer;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof InboxRecordId that)) {
      return false;
    }
    return Objects.equals(messageId, that.messageId) && Objects.equals(consumer, that.consumer);
  }

  @Override
  public int hashCode() {
    return Objects.hash(messageId, consumer);
  }
}
