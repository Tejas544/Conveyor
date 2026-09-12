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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
  @Autowired private PlatformTransactionManager transactionManager;

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
   * BUG-0049, found live via Phase 15's own load test — a genuinely concurrent race, not the
   * sequential "reply arrives after the sweep already committed" case the two tests above cover.
   * {@link SagaTimeoutSweeper#sweepOnce()} claims the row with {@code SELECT ... FOR UPDATE} and
   * holds that lock for the whole {@code applyTimeoutPolicy} transaction; before this fix, a
   * concurrently-running reply handler read the saga via a plain, unlocked {@code findByOrderId}
   * and could see the pre-timeout {@code RESERVING_INVENTORY} state under Postgres's MVCC even
   * while the sweep's transaction was still open, decide the saga was healthy, and — once its own
   * write finally went through after the sweep committed — silently clobber the sweep's {@code
   * ABORTED} transition and drive the saga all the way to {@code COMPLETED}: order confirmed and
   * charged for real, while {@code orders.status} (updated directly by the sweep's own {@code
   * OrderCancelled}) stayed {@code CANCELLED} forever. Reproduced here by holding the sweep's
   * transaction open on a latch until the reply handler has had a chance to attempt its own read,
   * proving the reply handler blocks instead of reading stale data.
   */
  @Test
  void lateInventoryReservedRacingAConcurrentTimeoutSweepNeverClobbersTheAbort() throws Exception {
    UUID orderId = startSaga();
    backdateDeadline(orderId);

    CountDownLatch sweepHoldingLock = new CountDownLatch(1);
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
    UUID reservationId = UUID.randomUUID();
    // A fixed hold, not a signal from the reply thread: the whole point is that an *unlocked* read
    // never blocks at all, so any signal fired from inside the reply thread's own call would race
    // arbitrarily against how far that call has actually gotten — proving nothing either way. A
    // hold long enough for a same-JVM, same-DB call to comfortably complete end-to-end is what
    // actually forces the read to land while this transaction is still open.
    Duration sweepHoldDuration = Duration.ofMillis(1500);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> sweepFuture =
          executor.submit(
              () ->
                  transactionTemplate.executeWithoutResult(
                      status -> {
                        // The real production lock: sweepOnce()'s own SELECT ... FOR UPDATE claim
                        // query, not a direct applyTimeoutPolicy call — this transaction must hold
                        // the exact same row lock the real sweeper takes for this test to prove
                        // anything about the real race.
                        int claimed = sweeper.sweepOnce();
                        assertThat(claimed).isEqualTo(1);
                        sweepHoldingLock.countDown();
                        // Hold the row lock open well past when the reply handler will have
                        // attempted its own read — if that read were unlocked, it would race in
                        // right here, during this window, exactly as it did live under Phase 15's
                        // real load.
                        sleepUninterruptibly(sweepHoldDuration);
                      }));

      sweepHoldingLock.await(5, TimeUnit.SECONDS);
      Future<?> replyFuture =
          executor.submit(
              () ->
                  orchestrationService.handleInventoryReserved(
                      UUID.randomUUID(),
                      orderId,
                      List.of(reservationId),
                      List.of(new InventoryItemPayload("SKU-1", 2))));

      sweepFuture.get(10, TimeUnit.SECONDS);
      replyFuture.get(10, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }

    SagaInstance saga = sagaInstanceRepository.findByOrderId(orderId).orElseThrow();
    assertThat(saga.getState())
        .as("the sweep's abort must win — the late reply must never resurrect the saga")
        .isEqualTo(SagaState.ABORTED);

    OutboxRecord release = onlyRecordFor(orderId, "ReleaseInventory");
    JsonNode payload = objectMapper.valueToTree(release.getPayload()).get("payload");
    assertThat(payload.get("reservationIds").get(0).asText()).isEqualTo(reservationId.toString());

    long chargeCommands =
        outboxRecordRepository.findAll().stream()
            .filter(
                r ->
                    r.getAggregateId().equals(orderId.toString())
                        && r.getEventType().equals("ChargePayment"))
            .count();
    assertThat(chargeCommands).as("the happy path must never have been resumed").isEqualTo(0);
  }

  private static void sleepUninterruptibly(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
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
