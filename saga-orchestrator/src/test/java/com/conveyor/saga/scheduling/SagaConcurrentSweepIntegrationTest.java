package com.conveyor.saga.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.contracts.events.OrderItemPayload;
import com.conveyor.saga.domain.SagaInstance;
import com.conveyor.saga.domain.SagaState;
import com.conveyor.saga.outbox.OutboxRecordRepository;
import com.conveyor.saga.repository.SagaInstanceRepository;
import com.conveyor.saga.service.SagaOrchestrationService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * PLAN.md Phase 6: "two orchestrator replicas run concurrently against the same saga backlog; no
 * saga is double-driven." {@code FOR UPDATE SKIP LOCKED} (ARCHITECTURE.md §7.4) means two
 * concurrent sweeps never process the same expired saga — simulated here as two threads calling
 * {@link SagaTimeoutSweeper#sweepOnce()} at once, standing in for two replicas' scheduled ticks.
 */
class SagaConcurrentSweepIntegrationTest extends AbstractIntegrationTest {

  private static final int SAGA_COUNT = 10;

  @Autowired private SagaOrchestrationService orchestrationService;
  @Autowired private SagaInstanceRepository sagaInstanceRepository;
  @Autowired private SagaTimeoutSweeper sweeper;
  @Autowired private OutboxRecordRepository outboxRecordRepository;

  @Test
  void concurrentSweepsNeverDoubleDriveTheSameSaga() throws Exception {
    List<UUID> orderIds = IntStream.range(0, SAGA_COUNT).mapToObj(i -> startExpiredSaga()).toList();

    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch startLine = new CountDownLatch(1);
    try {
      List<Future<Integer>> results =
          List.of(
              executor.submit(() -> runAfterLatch(startLine)),
              executor.submit(() -> runAfterLatch(startLine)));
      startLine.countDown();

      int totalClaimed = 0;
      for (Future<Integer> result : results) {
        totalClaimed += result.get(30, TimeUnit.SECONDS);
      }
      assertThat(totalClaimed).isEqualTo(SAGA_COUNT);
    } finally {
      executor.shutdownNow();
    }

    for (UUID orderId : orderIds) {
      SagaInstance saga = sagaInstanceRepository.findByOrderId(orderId).orElseThrow();
      assertThat(saga.getState()).isEqualTo(SagaState.ABORTED);

      long cancelledCount =
          outboxRecordRepository.findAll().stream()
              .filter(
                  r ->
                      r.getAggregateId().equals(orderId.toString())
                          && r.getEventType().equals("OrderCancelled"))
              .count();
      assertThat(cancelledCount)
          .as("exactly one OrderCancelled for order %s", orderId)
          .isEqualTo(1);
    }
  }

  private int runAfterLatch(CountDownLatch startLine) {
    try {
      startLine.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    int claimed = 0;
    // Repeated sweeps: a single pass may not claim every row if the other thread's transaction is
    // mid-flight and holding a lock on the rest; the sum across both threads must still equal
    // SAGA_COUNT, never more.
    for (int i = 0; i < 20; i++) {
      int n = sweeper.sweepOnce();
      claimed += n;
      if (n == 0) {
        try {
          Thread.sleep(20);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
    return claimed;
  }

  private UUID startExpiredSaga() {
    UUID orderId = UUID.randomUUID();
    orchestrationService.startSaga(
        UUID.randomUUID(),
        orderId,
        List.of(new OrderItemPayload("SKU-1", 1, new BigDecimal("5.00"))),
        new BigDecimal("5.00"),
        "USD",
        "tok_test_visa");
    SagaInstance saga = sagaInstanceRepository.findByOrderId(orderId).orElseThrow();
    saga.setDeadlineAt(Instant.now().minusSeconds(5));
    sagaInstanceRepository.saveAndFlush(saga);
    return orderId;
  }
}
