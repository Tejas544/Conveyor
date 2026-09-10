package com.conveyor.contracts.events;

import java.math.BigDecimal;

/** ARCHITECTURE.md §6.3 — the {@code payload} of a {@code ChargePayment} command. */
public record ChargePaymentPayload(
    BigDecimal amount, String currency, String paymentMethodToken, String idempotencyKey) {

  public static final String EVENT_TYPE = "ChargePayment";
  public static final int SCHEMA_VERSION = 1;
}
