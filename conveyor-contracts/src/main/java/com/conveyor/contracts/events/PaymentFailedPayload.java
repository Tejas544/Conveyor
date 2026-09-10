package com.conveyor.contracts.events;

/** ARCHITECTURE.md §6.3 — the {@code payload} of a {@code PaymentFailed} reply. */
public record PaymentFailedPayload(String reason, boolean retryable) {

  public static final String EVENT_TYPE = "PaymentFailed";
  public static final int SCHEMA_VERSION = 1;

  public static final String REASON_DECLINED = "DECLINED";
  public static final String REASON_GATEWAY_ERROR = "GATEWAY_ERROR";
  public static final String REASON_TIMEOUT = "TIMEOUT";
}
