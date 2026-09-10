package com.conveyor.dispatch.notification;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * ARCHITECTURE.md §5.5. Append-only notification log; document-shaped because the payload differs
 * per channel/template, is written once, and never joined.
 */
@Document(collection = "notifications")
public class NotificationDocument {

  @Id private String id;
  private String orderId;
  private String channel;
  private String template;
  private String recipient;
  private String renderedBody;
  private String status;
  private Instant sentAt;

  protected NotificationDocument() {}

  public NotificationDocument(
      String orderId,
      String channel,
      String template,
      String recipient,
      String renderedBody,
      String status,
      Instant sentAt) {
    this(null, orderId, channel, template, recipient, renderedBody, status, sentAt);
  }

  /**
   * {@code id} deterministic and caller-assigned (rather than Mongo-generated) is what makes {@link
   * com.conveyor.dispatch.service.DispatchService#writeNotification} an idempotent upsert under
   * redelivery — see that method's Javadoc.
   */
  public NotificationDocument(
      String id,
      String orderId,
      String channel,
      String template,
      String recipient,
      String renderedBody,
      String status,
      Instant sentAt) {
    this.id = id;
    this.orderId = orderId;
    this.channel = channel;
    this.template = template;
    this.recipient = recipient;
    this.renderedBody = renderedBody;
    this.status = status;
    this.sentAt = sentAt;
  }

  public String getId() {
    return id;
  }

  public String getOrderId() {
    return orderId;
  }

  public String getChannel() {
    return channel;
  }

  public String getTemplate() {
    return template;
  }

  public String getRecipient() {
    return recipient;
  }

  public String getRenderedBody() {
    return renderedBody;
  }

  public String getStatus() {
    return status;
  }

  public Instant getSentAt() {
    return sentAt;
  }
}
