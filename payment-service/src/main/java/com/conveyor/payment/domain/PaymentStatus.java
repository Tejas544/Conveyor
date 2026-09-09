package com.conveyor.payment.domain;

/** ARCHITECTURE.md §5.4. */
public enum PaymentStatus {
  AUTHORIZED,
  CAPTURED,
  FAILED,
  REFUNDED
}
