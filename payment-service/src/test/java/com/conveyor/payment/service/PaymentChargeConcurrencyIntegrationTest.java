package com.conveyor.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.payment.domain.PaymentStatus;
import com.conveyor.payment.outbox.OutboxRecord;
import com.conveyor.payment.outbox.OutboxRecordRepository;
import com.conveyor.payment.repository.PaymentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * PLAN.md Phase 5 — <strong>the important test</strong>: the same {@code ChargePayment} (same
 * {@code eventId} and {@code idempotencyKey}) delivered 5× concurrently → exactly one {@code
 * payments} row in {@code CAPTURED}, five identical {@code PaymentCharged} replies. Mirrors {@link
 * com.conveyor.payment.messaging.PaymentCommandListener}'s retry-on-conflict exactly (see that
 * class's Javadoc for why one retry always suffices) since this drives the service directly rather
 * than through Kafka — a single partition would serialize the deliveries and never exercise the
 * race at all.
 */
class PaymentChargeConcurrencyIntegrationTest extends AbstractIntegrationTest {

  private static final int CONCURRENT_DELIVERIES = 5;

  @Autowired private PaymentChargeService paymentChargeService;
  @Autowired private PaymentRepository paymentRepository;
  @Autowired private OutboxRecordRepository outboxRecordRepository;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void sameChargeDeliveredFiveTimesConcurrentlyProducesOnePaymentAndFiveIdenticalReplies()
      throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    String idempotencyKey = "saga-" + UUID.randomUUID() + ":CHARGE_PAYMENT";
    BigDecimal amount = new BigDecimal("49.99");

    ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_DELIVERIES);
    CountDownLatch ready = new CountDownLatch(CONCURRENT_DELIVERIES);
    CountDownLatch start = new CountDownLatch(1);

    for (int i = 0; i < CONCURRENT_DELIVERIES; i++) {
      executor.submit(
          () -> {
            ready.countDown();
            try {
              start.await();
              chargeWithRetryOnRace(eventId, orderId, amount, idempotencyKey);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
    }

    assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
    start.countDown();
    executor.shutdown();
    assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

    assertThat(paymentRepository.findByOrderId(orderId)).isPresent();
    long capturedPayments =
        paymentRepository.findAll().stream()
            .filter(p -> p.getOrderId().equals(orderId) && p.getStatus() == PaymentStatus.CAPTURED)
            .count();
    assertThat(capturedPayments).isEqualTo(1);

    var replies =
        outboxRecordRepository.findAll().stream()
            .filter(
                r ->
                    r.getAggregateId().equals(orderId.toString())
                        && r.getEventType().equals("PaymentCharged"))
            .toList();
    assertThat(replies).hasSize(CONCURRENT_DELIVERIES);

    // objectMapper here is the same Spring-managed ObjectMapper bean Hibernate's JSON column
    // mapping now reuses (conveyor-common's HibernateJsonFormatMapperAutoConfiguration —
    // BUGS.md BUG-0010) rather than an internally-built one that picked up jackson-module-scala
    // from the test classpath, so nested payload content is consistently java.util.Map.
    Set<JsonNode> distinctPaymentIds = new HashSet<>();
    for (OutboxRecord reply : replies) {
      JsonNode payload = objectMapper.valueToTree(reply.getPayload()).get("payload");
      distinctPaymentIds.add(payload.get("paymentId"));
    }
    assertThat(distinctPaymentIds).as("all 5 replies reference the same paymentId").hasSize(1);
  }

  /** The same retry-once-on-conflict pattern {@code PaymentCommandListener} uses in production. */
  private void chargeWithRetryOnRace(
      UUID eventId, UUID orderId, BigDecimal amount, String idempotencyKey) {
    try {
      paymentChargeService.handleChargePayment(
          eventId, orderId, null, null, amount, "USD", idempotencyKey);
    } catch (DataIntegrityViolationException e) {
      paymentChargeService.handleChargePayment(
          eventId, orderId, null, null, amount, "USD", idempotencyKey);
    }
  }
}
