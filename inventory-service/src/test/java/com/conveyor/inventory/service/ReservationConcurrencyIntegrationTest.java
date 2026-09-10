package com.conveyor.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.contracts.events.InventoryItemPayload;
import com.conveyor.inventory.domain.StockItem;
import com.conveyor.inventory.outbox.OutboxRecordRepository;
import com.conveyor.inventory.repository.StockItemRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * PLAN.md Phase 4 — <strong>the important test</strong>: 50 threads reserve the last unit of a SKU
 * simultaneously; exactly 1 succeeds, 49 get {@code INSUFFICIENT_STOCK}, and {@code reserved} ends
 * at exactly 1 (ADR-9's guarded conditional {@code UPDATE} — no application-level lock is ever
 * taken). Repeated 20× to rule out a lucky pass.
 */
class ReservationConcurrencyIntegrationTest extends AbstractIntegrationTest {

  private static final int THREAD_COUNT = 50;

  @Autowired private InventoryReservationService reservationService;
  @Autowired private StockItemRepository stockItemRepository;
  @Autowired private OutboxRecordRepository outboxRecordRepository;

  @RepeatedTest(20)
  void exactlyOneOfFiftyConcurrentReservationsSucceedsForTheLastUnit() throws Exception {
    String sku = "SKU-CONC-" + UUID.randomUUID();
    stockItemRepository.saveAndFlush(new StockItem(sku, 1, 0, 1));

    List<UUID> orderIds = new ArrayList<>();
    for (int i = 0; i < THREAD_COUNT; i++) {
      orderIds.add(UUID.randomUUID());
    }

    ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
    CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
    CountDownLatch start = new CountDownLatch(1);

    for (UUID orderId : orderIds) {
      executor.submit(
          () -> {
            ready.countDown();
            try {
              start.await();
              reservationService.handleReserveInventory(
                  UUID.randomUUID(),
                  orderId,
                  null,
                  null,
                  List.of(new InventoryItemPayload(sku, 1)));
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
    }

    assertThat(ready.await(10, TimeUnit.SECONDS)).as("all threads reached the start line").isTrue();
    start.countDown();
    executor.shutdown();
    assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).as("all threads finished").isTrue();

    StockItem finalState = stockItemRepository.findById(sku).orElseThrow();
    assertThat(finalState.getReserved()).isEqualTo(1);
    assertThat(finalState.getAvailable()).isZero();

    long succeeded =
        outboxRecordRepository.findAll().stream()
            .filter(
                r ->
                    r.getEventType().equals("InventoryReserved")
                        && orderIds.contains(UUID.fromString(r.getAggregateId())))
            .count();
    long failed =
        outboxRecordRepository.findAll().stream()
            .filter(
                r ->
                    r.getEventType().equals("InventoryReservationFailed")
                        && orderIds.contains(UUID.fromString(r.getAggregateId())))
            .count();

    assertThat(succeeded).isEqualTo(1);
    assertThat(failed).isEqualTo(THREAD_COUNT - 1);
  }
}
