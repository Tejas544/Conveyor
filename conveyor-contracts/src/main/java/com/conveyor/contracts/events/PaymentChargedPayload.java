package com.conveyor.contracts.events;

import java.math.BigDecimal;
import java.util.UUID;

/** ARCHITECTURE.md §6.3 — the {@code payload} of a {@code PaymentCharged} reply. */
public record PaymentChargedPayload(
    UUID paymentId, BigDecimal amount, String currency, String gatewayReference) {

  public static final String EVENT_TYPE = "PaymentCharged";
  public static final int SCHEMA_VERSION = 1;
}
