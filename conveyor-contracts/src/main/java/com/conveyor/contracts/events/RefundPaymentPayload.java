package com.conveyor.contracts.events;

import java.math.BigDecimal;
import java.util.UUID;

/** ARCHITECTURE.md §6.3 — the {@code payload} of a {@code RefundPayment} command. */
public record RefundPaymentPayload(UUID paymentId, BigDecimal amount, String idempotencyKey) {

  public static final String EVENT_TYPE = "RefundPayment";
  public static final int SCHEMA_VERSION = 1;
}
