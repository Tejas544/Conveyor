package com.conveyor.dispatch.web.dto;

import com.conveyor.dispatch.notification.NotificationDocument;
import java.time.Instant;

/** ARCHITECTURE.md §10.4: {@code GET /notifications?orderId=}. */
public record NotificationResponse(
    String id,
    String orderId,
    String channel,
    String template,
    String recipient,
    String status,
    Instant sentAt) {

  public static NotificationResponse from(NotificationDocument document) {
    return new NotificationResponse(
        document.getId(),
        document.getOrderId(),
        document.getChannel(),
        document.getTemplate(),
        document.getRecipient(),
        document.getStatus(),
        document.getSentAt());
  }
}
