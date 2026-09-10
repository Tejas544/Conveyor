package com.conveyor.payment.web.dto;

import com.conveyor.payment.domain.Payment;
import com.conveyor.payment.domain.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** ARCHITECTURE.md §10.4: {@code GET /payments/{orderId}}. */
public record PaymentResponse(
    UUID id,
    UUID orderId,
    BigDecimal amount,
    String currency,
    PaymentStatus status,
    String gatewayReference,
    Instant createdAt,
    Instant updatedAt) {

  public static PaymentResponse from(Payment payment) {
    return new PaymentResponse(
        payment.getId(),
        payment.getOrderId(),
        payment.getAmount(),
        payment.getCurrency(),
        payment.getStatus(),
        payment.getGatewayReference(),
        payment.getCreatedAt(),
        payment.getUpdatedAt());
  }
}
