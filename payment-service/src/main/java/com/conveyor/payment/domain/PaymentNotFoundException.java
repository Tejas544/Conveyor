package com.conveyor.payment.domain;

import java.util.UUID;

/**
 * PLAN.md Phase 5: refunding a non-existent payment "fails loudly rather than silently succeeding"
 * — this is that loud failure.
 */
public class PaymentNotFoundException extends RuntimeException {

  private PaymentNotFoundException(String message) {
    super(message);
  }

  public static PaymentNotFoundException forPaymentId(UUID paymentId) {
    return new PaymentNotFoundException("Payment not found: " + paymentId);
  }

  public static PaymentNotFoundException forOrderId(UUID orderId) {
    return new PaymentNotFoundException("No payment found for order: " + orderId);
  }
}
