package com.conveyor.saga.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.contracts.events.InventoryItemPayload;
import com.conveyor.contracts.events.OrderItemPayload;
import com.conveyor.saga.domain.SagaInstance;
import com.conveyor.saga.domain.SagaState;
import com.conveyor.saga.domain.SagaStep;
import com.conveyor.saga.domain.SagaSteps;
import com.conveyor.saga.domain.StepDirection;
import com.conveyor.saga.domain.StepStatus;
import com.conveyor.saga.outbox.OutboxRecord;
import com.conveyor.saga.outbox.OutboxRecordRepository;
import com.conveyor.saga.repository.SagaInstanceRepository;
import com.conveyor.saga.repository.SagaStepRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * PLAN.md Phase 6: E2E happy path driven directly against {@link SagaOrchestrationService} — the
 * same "exercise it directly, don't fake collaborators" approach Phase 4/5 used for their own
 * commands, except here the collaborators (inventory-service, payment-service) are real services in
 * other modules, so their replies are simulated exactly as they would appear on the wire.
 */
class SagaHappyPathIntegrationTest extends AbstractIntegrationTest {

  @Autowired private SagaOrchestrationService orchestrationService;
  @Autowired private SagaInstanceRepository sagaInstanceRepository;
  @Autowired private SagaStepRepository sagaStepRepository;
  @Autowired private OutboxRecordRepository outboxRecordRepository;

  @Test
  void placingAnOrderReservingInventoryAndChargingPaymentConfirmsTheOrder() {
    UUID orderId = UUID.randomUUID();
    List<OrderItemPayload> items =
        List.of(new OrderItemPayload("SKU-1", 2, new BigDecimal("10.00")));

    orchestrationService.startSaga(
        UUID.randomUUID(), orderId, items, new BigDecimal("20.00"), "USD", "tok_test_visa");

    SagaInstance saga = sagaInstanceRepository.findByOrderId(orderId).orElseThrow();
    assertThat(saga.getState()).isEqualTo(SagaState.RESERVING_INVENTORY);
    OutboxRecord reserveCommand = latestCommandFor(orderId, "ReserveInventory");
    assertThat(reserveCommand).isNotNull();

    UUID reservationId = UUID.randomUUID();
    orchestrationService.handleInventoryReserved(
        UUID.randomUUID(),
        orderId,
        List.of(reservationId),
        List.of(new InventoryItemPayload("SKU-1", 2)));

    saga = sagaInstanceRepository.findByOrderId(orderId).orElseThrow();
    assertThat(saga.getState()).isEqualTo(SagaState.CHARGING_PAYMENT);
    assertThat(latestCommandFor(orderId, "ChargePayment")).isNotNull();

    UUID paymentEventId = UUID.randomUUID();
    orchestrationService.handlePaymentCharged(
        paymentEventId, orderId, UUID.randomUUID(), new BigDecimal("20.00"), "gw_ref_1");
    saga = sagaInstanceRepository.findByOrderId(orderId).orElseThrow();
    assertThat(saga.getState()).isEqualTo(SagaState.CONFIRMING);

    orchestrationService.tryConfirm(saga.getId());

    saga = sagaInstanceRepository.findByOrderId(orderId).orElseThrow();
    assertThat(saga.getState()).isEqualTo(SagaState.COMPLETED);
    assertThat(saga.getDeadlineAt()).isNull();

    OutboxRecord confirmedEvent = latestCommandFor(orderId, "OrderConfirmed");
    assertThat(confirmedEvent).isNotNull();
    assertThat(confirmedEvent.getTopic()).isEqualTo("conveyor.order.events.v1");

    List<SagaStep> steps = sagaStepRepository.findBySagaIdOrderBySeqAsc(saga.getId());
    assertThat(steps)
        .extracting(SagaStep::getStep, SagaStep::getDirection, SagaStep::getStatus)
        .containsExactly(
            tuple(SagaSteps.RESERVE_INVENTORY, StepDirection.FORWARD, StepStatus.STARTED),
            tuple(SagaSteps.RESERVE_INVENTORY, StepDirection.FORWARD, StepStatus.SUCCEEDED),
            tuple(SagaSteps.CHARGE_PAYMENT, StepDirection.FORWARD, StepStatus.STARTED),
            tuple(SagaSteps.CHARGE_PAYMENT, StepDirection.FORWARD, StepStatus.SUCCEEDED),
            tuple(SagaSteps.CONFIRM_ORDER, StepDirection.FORWARD, StepStatus.STARTED),
            tuple(SagaSteps.CONFIRM_ORDER, StepDirection.FORWARD, StepStatus.SUCCEEDED));
  }

  private static org.assertj.core.groups.Tuple tuple(Object... values) {
    return org.assertj.core.groups.Tuple.tuple(values);
  }

  private OutboxRecord latestCommandFor(UUID orderId, String eventType) {
    return outboxRecordRepository.findAll().stream()
        .filter(
            r ->
                r.getAggregateId().equals(orderId.toString()) && r.getEventType().equals(eventType))
        .reduce((first, second) -> second)
        .orElse(null);
  }
}
