package com.conveyor.saga.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.contracts.events.InventoryItemPayload;
import com.conveyor.contracts.events.OrderCancelledPayload;
import com.conveyor.contracts.events.OrderItemPayload;
import com.conveyor.saga.domain.SagaInstance;
import com.conveyor.saga.domain.SagaState;
import com.conveyor.saga.outbox.OutboxRecord;
import com.conveyor.saga.outbox.OutboxRecordRepository;
import com.conveyor.saga.repository.SagaInstanceRepository;
import com.conveyor.saga.service.SagaOrchestrationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * PLAN.md Phase 6: "Inventory never replies" / "Release keeps failing" are simulated by never
 * sending the reply and directly backdating {@code deadline_at}, then driving {@link
 * SagaTimeoutSweeper} manually — real crash-timing races are Phase 11's chaos matrix, not this
 * phase's own unit-level timeout tests (ARCHITECTURE.md §7.4, §14).
 */
class SagaTimeoutIntegrationTest extends AbstractIntegrationTest {

  @Autowired private SagaOrchestrationService orchestrationService;
  @Autowired private SagaInstanceRepository sagaInstanceRepository;
  @Autowired private SagaTimeoutSweeper sweeper;
  @Autowired private OutboxRecordRepository outboxRecordRepository;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void forwardTimeoutWhileReservingInventoryAbortsTheSaga() {
    UUID orderId = startSaga();
    backdateDeadline(orderId);

    int claimed = sweeper.sweepOnce();

    assertThat(claimed).isEqualTo(1);
    SagaInstance saga = sagaInstanceRepository.findByOrderId(orderId).orElseThrow();
    assertThat(saga.getState()).isEqualTo(SagaState.ABORTED);

    OutboxRecord cancelled = onlyRecordFor(orderId, "OrderCancelled");
    JsonNode payload = objectMapper.valueToTree(cancelled.getPayload()).get("payload");
    assertThat(payload.get("reason").asText())
        .isEqualTo(OrderCancelledPayload.REASON_RESERVE_INVENTORY_TIMEOUT);
  }

  @Test
  void compensationTimeoutRetriesWithBackoffThenEscalatesAndRetryEndpointRedrivesIt() {
    UUID orderId = startSaga();
    UUID reservationId = UUID.randomUUID();
    orchestrationService.handleInventoryReserved(
        UUID.randomUUID(),
        orderId,
        List.of(reservationId),
        List.of(new InventoryItemPayload("SKU-1", 2)));
    orchestrationService.handlePaymentFailed(UUID.randomUUID(), orderId, "DECLINED");

    SagaInstance saga = sagaInstanceRepository.findByOrderId(orderId).orElseThrow();
    assertThat(saga.getState()).isEqualTo(SagaState.COMPENSATING_INVENTORY);

    // application-test.yml: max-compensation-attempts = 3. Release keeps "failing" (never replied
    // to) three times running out the clock, which must escalate rather than retry forever.
    backdateDeadline(orderId);
    sweeper.sweepOnce();
    backdateDeadline(orderId);
    sweeper.sweepOnce();
    backdateDeadline(orderId);
    sweeper.sweepOnce();

    saga = sagaInstanceRepository.findByOrderId(orderId).orElseThrow();
    assertThat(saga.getState()).isEqualTo(SagaState.NEEDS_INTERVENTION);
    assertThat(saga.getDeadlineAt()).isNull();

    // PLAN.md Phase 6: "not silently aborted" — an operator can re-drive it.
    orchestrationService.retrySaga(saga.getId());

    saga = sagaInstanceRepository.findByOrderId(orderId).orElseThrow();
    assertThat(saga.getState()).isEqualTo(SagaState.COMPENSATING_INVENTORY);
    assertThat(saga.getDeadlineAt()).isAfter(Instant.now());

    orchestrationService.handleInventoryReleased(
        UUID.randomUUID(), orderId, List.of(reservationId));
    saga = sagaInstanceRepository.findByOrderId(orderId).orElseThrow();
    assertThat(saga.getState()).isEqualTo(SagaState.ABORTED);
  }

  /**
   * BUG-0027, found live via Phase 11's chaos matrix: a {@code RESERVING_INVENTORY} timeout assumes
   * no reply ever arrived, so nothing was reserved — true in general, but not when the reply is
   * only *late* (redelivery after a crashed {@code SagaReplyListener} lost the race against the
   * sweeper). Before this fix, {@code handleInventoryReserved} silently discarded a reply for a
   * saga already {@code ABORTED}, orphaning a real reservation forever (INV-ORD-02: a CANCELLED
   * order still holding a HELD reservation).
   */
  @Test
  void lateInventoryReservedAfterAbortReleasesTheReservationInsteadOfOrphaningIt() {
    UUID orderId = startSaga();
    backdateDeadline(orderId);
    sweeper.sweepOnce();
    SagaInstance saga = sagaInstanceRepository.findByOrderId(orderId).orElseThrow();
    assertThat(saga.getState()).isEqualTo(SagaState.ABORTED);

    UUID reservationId = UUID.randomUUID();
    orchestrationService.handleInventoryReserved(
        UUID.randomUUID(),
        orderId,
        List.of(reservationId),
        List.of(new InventoryItemPayload("SKU-1", 2)));

    saga = sagaInstanceRepository.findByOrderId(orderId).orElseThrow();
    assertThat(saga.getState())
        .as("a terminal saga's own outcome does not change")
        .isEqualTo(SagaState.ABORTED);

    OutboxRecord release = onlyRecordFor(orderId, "ReleaseInventory");
    JsonNode payload = objectMapper.valueToTree(release.getPayload()).get("payload");
    assertThat(payload.get("reservationIds").get(0).asText()).isEqualTo(reservationId.toString());
  }

  /**
   * BUG-0027, found live via Phase 11's chaos matrix — "money moved, nobody told," the exact danger
   * ARCHITECTURE.md §14 names as most dangerous, reached here via a redelivery race rather than the
   * {@code payment.after-commit-before-publish} point itself: a {@code CHARGING_PAYMENT} timeout is
   * handled as "nothing was charged, only inventory needs releasing" — true in general, but not
   * when this is a *late* reply for a charge that crashed and was redelivered after the sweeper
   * already gave up. Before this fix, {@code handlePaymentCharged} silently discarded a reply for a
   * saga already {@code ABORTED}, leaving the customer charged for a cancelled order forever
   * (INV-ORD-03).
   */
  @Test
  void latePaymentChargedAfterAbortRefundsThePaymentInsteadOfLeavingTheCustomerCharged() {
    UUID orderId = startSaga();
    UUID reservationId = UUID.randomUUID();
    orchestrationService.handleInventoryReserved(
        UUID.randomUUID(),
        orderId,
        List.of(reservationId),
        List.of(new InventoryItemPayload("SKU-1", 2)));

    backdateDeadline(orderId);
    sweeper.sweepOnce(); // CHARGING_PAYMENT timeout -> beginInventoryCompensation
    orchestrationService.handleInventoryReleased(
        UUID.randomUUID(), orderId, List.of(reservationId));

    SagaInstance saga = sagaInstanceRepository.findByOrderId(orderId).orElseThrow();
    assertThat(saga.getState()).isEqualTo(SagaState.ABORTED);

    UUID paymentId = UUID.randomUUID();
    orchestrationService.handlePaymentCharged(
        UUID.randomUUID(), orderId, paymentId, new BigDecimal("20.00"), "gw_ref_late");

    saga = sagaInstanceRepository.findByOrderId(orderId).orElseThrow();
    assertThat(saga.getState())
        .as("a terminal saga's own outcome does not change")
        .isEqualTo(SagaState.ABORTED);

    OutboxRecord refund = onlyRecordFor(orderId, "RefundPayment");
    JsonNode payload = objectMapper.valueToTree(refund.getPayload()).get("payload");
    assertThat(payload.get("paymentId").asText()).isEqualTo(paymentId.toString());
  }

  /**
   * BUG-0036, found live via Phase 13's own HPA load test — a real gap in BUG-0027's fix above, not
   * a duplicate of it. That fix only checked {@code SagaState.ABORTED}; this saga is instead still
   * {@code COMPENSATING_INVENTORY} — {@code RELEASE_INVENTORY} commanded but its reply not yet
   * processed, exactly the window a live run observed stretching to three retries over several
   * minutes under load. Before this fix, {@code handlePaymentCharged} hit the `else` branch
   * ("Ignoring PaymentCharged for saga ... in state COMPENSATING_INVENTORY") and silently dropped
   * the reply: charged, never refunded, no trace above a WARN log line.
   */
  @Test
  void latePaymentChargedWhileCompensationStillRetryingRefundsThePaymentInsteadOfDroppingIt() {
    UUID orderId = startSaga();
    UUID reservationId = UUID.randomUUID();
    orchestrationService.handleInventoryReserved(
        UUID.randomUUID(),
        orderId,
        List.of(reservationId),
        List.of(new InventoryItemPayload("SKU-1", 2)));

    backdateDeadline(orderId);
    sweeper.sweepOnce(); // CHARGING_PAYMENT timeout -> beginInventoryCompensation

    SagaInstance saga = sagaInstanceRepository.findByOrderId(orderId).orElseThrow();
    assertThat(saga.getState())
        .as("RELEASE_INVENTORY commanded but not yet replied to — not ABORTED yet")
        .isEqualTo(SagaState.COMPENSATING_INVENTORY);

    UUID paymentId = UUID.randomUUID();
    orchestrationService.handlePaymentCharged(
        UUID.randomUUID(), orderId, paymentId, new BigDecimal("20.00"), "gw_ref_late");

    saga = sagaInstanceRepository.findByOrderId(orderId).orElseThrow();
    assertThat(saga.getState())
        .as("the in-flight inventory compensation is untouched by this")
        .isEqualTo(SagaState.COMPENSATING_INVENTORY);

    OutboxRecord refund = onlyRecordFor(orderId, "RefundPayment");
    JsonNode payload = objectMapper.valueToTree(refund.getPayload()).get("payload");
    assertThat(payload.get("paymentId").asText()).isEqualTo(paymentId.toString());
  }

  private UUID startSaga() {
    UUID orderId = UUID.randomUUID();
    orchestrationService.startSaga(
        UUID.randomUUID(),
        orderId,
        List.of(new OrderItemPayload("SKU-1", 2, new BigDecimal("10.00"))),
        new BigDecimal("20.00"),
        "USD",
        "tok_test_visa");
    return orderId;
  }

  private void backdateDeadline(UUID orderId) {
    SagaInstance saga = sagaInstanceRepository.findByOrderId(orderId).orElseThrow();
    saga.setDeadlineAt(Instant.now().minusSeconds(5));
    sagaInstanceRepository.saveAndFlush(saga);
  }

  private OutboxRecord onlyRecordFor(UUID orderId, String eventType) {
    List<OutboxRecord> records =
        outboxRecordRepository.findAll().stream()
            .filter(
                r ->
                    r.getAggregateId().equals(orderId.toString())
                        && r.getEventType().equals(eventType))
            .toList();
    assertThat(records).hasSize(1);
    return records.get(0);
  }
}
