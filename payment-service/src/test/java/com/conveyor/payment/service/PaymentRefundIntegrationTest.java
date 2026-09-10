package com.conveyor.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.payment.domain.Payment;
import com.conveyor.payment.domain.PaymentNotFoundException;
import com.conveyor.payment.domain.PaymentStatus;
import com.conveyor.payment.outbox.OutboxRecordRepository;
import com.conveyor.payment.repository.PaymentRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * PLAN.md Phase 5: refund is idempotent; refunding a non-existent payment fails loudly rather than
 * silently succeeding.
 */
class PaymentRefundIntegrationTest extends AbstractIntegrationTest {

  @Autowired private PaymentChargeService paymentChargeService;
  @Autowired private PaymentRepository paymentRepository;
  @Autowired private OutboxRecordRepository outboxRecordRepository;

  @Test
  void refundingTheSamePaymentTwiceIsIdempotent() {
    Payment payment =
        paymentRepository.saveAndFlush(
            new Payment(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("30.00"),
                "USD",
                PaymentStatus.CAPTURED,
                "gw_original"));
    String idempotencyKey = "saga-" + UUID.randomUUID() + ":REFUND_PAYMENT";

    paymentChargeService.handleRefundPayment(
        UUID.randomUUID(),
        payment.getOrderId(),
        null,
        null,
        payment.getId(),
        payment.getAmount(),
        idempotencyKey);
    paymentChargeService.handleRefundPayment(
        UUID.randomUUID(),
        payment.getOrderId(),
        null,
        null,
        payment.getId(),
        payment.getAmount(),
        idempotencyKey);

    Payment refunded = paymentRepository.findById(payment.getId()).orElseThrow();
    assertThat(refunded.getStatus()).isEqualTo(PaymentStatus.REFUNDED);

    long refundReplies =
        outboxRecordRepository.findAll().stream()
            .filter(
                r ->
                    r.getAggregateId().equals(payment.getOrderId().toString())
                        && r.getEventType().equals("PaymentRefunded"))
            .count();
    assertThat(refundReplies).isEqualTo(2);
  }

  @Test
  void refundingANonExistentPaymentFailsLoudly() {
    UUID orderId = UUID.randomUUID();
    UUID unknownPaymentId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                paymentChargeService.handleRefundPayment(
                    UUID.randomUUID(),
                    orderId,
                    null,
                    null,
                    unknownPaymentId,
                    new BigDecimal("10.00"),
                    "key-1"))
        .isInstanceOf(PaymentNotFoundException.class);

    assertThat(
            outboxRecordRepository.findAll().stream()
                .anyMatch(r -> r.getAggregateId().equals(orderId.toString())))
        .as("nothing is written when the refund target doesn't exist")
        .isFalse();
  }
}
