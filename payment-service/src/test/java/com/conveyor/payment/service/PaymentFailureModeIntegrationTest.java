package com.conveyor.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.payment.gateway.MockPaymentGateway;
import com.conveyor.payment.outbox.OutboxRecord;
import com.conveyor.payment.outbox.OutboxRecordRepository;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * PLAN.md Phase 5: each failure mode ({@code DECLINE}, {@code TIMEOUT}, {@code GATEWAY_ERROR})
 * produces the right {@code PaymentFailed.reason} and {@code retryable} flag.
 */
class PaymentFailureModeIntegrationTest extends AbstractIntegrationTest {

  @Autowired private PaymentChargeService paymentChargeService;
  @Autowired private MockPaymentGateway gateway;
  @Autowired private OutboxRecordRepository outboxRecordRepository;

  @Test
  void declineProducesDeclinedReasonAndIsNotRetryable() {
    assertFailureMode("DECLINE", "DECLINED", false);
  }

  @Test
  void timeoutProducesTimeoutReasonAndIsRetryable() {
    assertFailureMode("TIMEOUT", "TIMEOUT", true);
  }

  @Test
  void gatewayErrorProducesGatewayErrorReasonAndIsRetryable() {
    assertFailureMode("ERROR", "GATEWAY_ERROR", true);
  }

  private void assertFailureMode(String mode, String expectedReason, boolean expectedRetryable) {
    gateway.armFailureMode(mode, 1.0);
    UUID orderId = UUID.randomUUID();
    String idempotencyKey = "saga-" + UUID.randomUUID() + ":CHARGE_PAYMENT";

    paymentChargeService.handleChargePayment(
        UUID.randomUUID(), orderId, null, null, new BigDecimal("20.00"), "USD", idempotencyKey);

    OutboxRecord reply =
        outboxRecordRepository.findAll().stream()
            .filter(r -> r.getAggregateId().equals(orderId.toString()))
            .findFirst()
            .orElseThrow();
    assertThat(reply.getEventType()).isEqualTo("PaymentFailed");

    @SuppressWarnings("unchecked")
    Map<String, Object> payload = (Map<String, Object>) reply.getPayload().get("payload");
    assertThat(payload.get("reason")).isEqualTo(expectedReason);
    assertThat(payload.get("retryable")).isEqualTo(expectedRetryable);
  }
}
