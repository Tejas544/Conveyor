package com.conveyor.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.payment.gateway.MockPaymentGateway;
import com.conveyor.payment.outbox.OutboxRecord;
import com.conveyor.payment.outbox.OutboxRecordRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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
  @Autowired private ObjectMapper objectMapper;

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

    // objectMapper here is the same Spring-managed ObjectMapper bean Hibernate's JSON column
    // mapping now reuses (conveyor-common's HibernateJsonFormatMapperAutoConfiguration —
    // BUGS.md BUG-0010) rather than an internally-built one that picked up jackson-module-scala
    // from the test classpath, so nested payload content is consistently java.util.Map.
    JsonNode payload = objectMapper.valueToTree(reply.getPayload()).get("payload");
    assertThat(payload.get("reason").asText()).isEqualTo(expectedReason);
    assertThat(payload.get("retryable").asBoolean()).isEqualTo(expectedRetryable);
  }
}
