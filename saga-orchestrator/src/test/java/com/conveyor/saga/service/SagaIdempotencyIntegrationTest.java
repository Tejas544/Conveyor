package com.conveyor.saga.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.contracts.events.InventoryItemPayload;
import com.conveyor.contracts.events.OrderItemPayload;
import com.conveyor.saga.domain.SagaInstance;
import com.conveyor.saga.domain.SagaState;
import com.conveyor.saga.outbox.OutboxRecord;
import com.conveyor.saga.outbox.OutboxRecordRepository;
import com.conveyor.saga.repository.SagaInstanceRepository;
import com.conveyor.saga.repository.SagaStepRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * PLAN.md Phase 6: "duplicate reply delivery does not advance the saga twice" and the
 * orchestrator-crash-recovery property — a message never acknowledged (offset not committed) is
 * redelivered with the <em>same</em> {@code eventId}, and the inbox table is what makes replaying
 * it a safe no-op rather than a double-advance, exactly like every other consumer in this project
 * (ARCHITECTURE.md §9).
 */
class SagaIdempotencyIntegrationTest extends AbstractIntegrationTest {

  @Autowired private SagaOrchestrationService orchestrationService;
  @Autowired private SagaInstanceRepository sagaInstanceRepository;
  @Autowired private SagaStepRepository sagaStepRepository;
  @Autowired private OutboxRecordRepository outboxRecordRepository;
  @Autowired private MeterRegistry meterRegistry;

  @Test
  void redeliveredInventoryReservedDoesNotAdvanceTheSagaTwiceOrResendChargePayment() {
    UUID orderId = UUID.randomUUID();
    orchestrationService.startSaga(
        UUID.randomUUID(),
        orderId,
        List.of(new OrderItemPayload("SKU-1", 2, new BigDecimal("10.00"))),
        new BigDecimal("20.00"),
        "USD",
        "tok_test_visa");

    UUID eventId = UUID.randomUUID();
    List<InventoryItemPayload> items = List.of(new InventoryItemPayload("SKU-1", 2));
    UUID reservationId = UUID.randomUUID();

    double before = duplicatesCount();

    // "The process died before the offset committed" == calling the handler again with the exact
    // same eventId, since that is indistinguishable from Kafka's point of view.
    orchestrationService.handleInventoryReserved(eventId, orderId, List.of(reservationId), items);
    orchestrationService.handleInventoryReserved(eventId, orderId, List.of(reservationId), items);
    orchestrationService.handleInventoryReserved(eventId, orderId, List.of(reservationId), items);

    assertThat(duplicatesCount() - before).isEqualTo(2.0);

    SagaInstance saga = sagaInstanceRepository.findByOrderId(orderId).orElseThrow();
    assertThat(saga.getState()).isEqualTo(SagaState.CHARGING_PAYMENT);
    assertThat(sagaStepRepository.findBySagaIdOrderBySeqAsc(saga.getId())).hasSize(3);

    List<OutboxRecord> chargeCommands =
        outboxRecordRepository.findAll().stream()
            .filter(
                r ->
                    r.getAggregateId().equals(orderId.toString())
                        && r.getEventType().equals("ChargePayment"))
            .toList();
    assertThat(chargeCommands).hasSize(1);
  }

  private double duplicatesCount() {
    var counter =
        meterRegistry
            .find("conveyor_inbox_duplicates_total")
            .tag("consumer", "saga-orchestrator")
            .counter();
    return counter == null ? 0.0 : counter.count();
  }
}
