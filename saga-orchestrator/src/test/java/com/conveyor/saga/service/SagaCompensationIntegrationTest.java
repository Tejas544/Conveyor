package com.conveyor.saga.service;

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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * PLAN.md Phase 6: the three compensation paths — inventory fails, payment declines, and payment
 * succeeds then the saga is aborted anyway (refund <em>and</em> release, both observed).
 */
class SagaCompensationIntegrationTest extends AbstractIntegrationTest {

  @Autowired private SagaOrchestrationService orchestrationService;
  @Autowired private SagaInstanceRepository sagaInstanceRepository;
  @Autowired private OutboxRecordRepository outboxRecordRepository;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void inventoryReservationFailureAbortsWithoutEverChargingPayment() {
    UUID orderId = startSaga();

    orchestrationService.handleInventoryReservationFailed(
        UUID.randomUUID(), orderId, "INSUFFICIENT_STOCK", List.of());

    SagaInstance saga = sagaInstanceRepository.findByOrderId(orderId).orElseThrow();
    assertThat(saga.getState()).isEqualTo(SagaState.ABORTED);
    assertThat(commandsFor(orderId, "ChargePayment")).isEmpty();

    OutboxRecord cancelled = onlyRecordFor(orderId, "OrderCancelled");
    JsonNode payload = payloadOf(cancelled);
    assertThat(payload.get("reason").asText())
        .isEqualTo(OrderCancelledPayload.REASON_INVENTORY_RESERVATION_FAILED);
    assertThat(payload.get("compensatedSteps")).isEmpty();
  }

  @Test
  void paymentDeclineReleasesInventoryAndCancelsTheOrder() {
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

    OutboxRecord release = onlyRecordFor(orderId, "ReleaseInventory");
    JsonNode releasePayload = payloadOf(release);
    assertThat(releasePayload.get("reservationIds").get(0).asText())
        .isEqualTo(reservationId.toString());

    orchestrationService.handleInventoryReleased(
        UUID.randomUUID(), orderId, List.of(reservationId));

    saga = sagaInstanceRepository.findByOrderId(orderId).orElseThrow();
    assertThat(saga.getState()).isEqualTo(SagaState.ABORTED);
    // §8.2: no refund, since no charge was ever captured.
    assertThat(commandsFor(orderId, "RefundPayment")).isEmpty();

    OutboxRecord cancelled = onlyRecordFor(orderId, "OrderCancelled");
    JsonNode payload = payloadOf(cancelled);
    assertThat(payload.get("reason").asText())
        .isEqualTo(OrderCancelledPayload.REASON_PAYMENT_DECLINED);
    assertThat(payload.get("compensatedSteps"))
        .extracting(JsonNode::asText)
        .containsExactly("RESERVE_INVENTORY");
  }

  @Test
  void operatorAbortAfterPaymentSucceedsRefundsAndReleases() {
    UUID orderId = startSaga();
    UUID reservationId = UUID.randomUUID();
    orchestrationService.handleInventoryReserved(
        UUID.randomUUID(),
        orderId,
        List.of(reservationId),
        List.of(new InventoryItemPayload("SKU-1", 2)));

    UUID paymentId = UUID.randomUUID();
    orchestrationService.handlePaymentCharged(
        UUID.randomUUID(), orderId, paymentId, new BigDecimal("20.00"), "gw_ref_2");

    SagaInstance saga = sagaInstanceRepository.findByOrderId(orderId).orElseThrow();
    assertThat(saga.getState()).isEqualTo(SagaState.CONFIRMING);

    // An operator aborts before the pivot (tryConfirm) ever runs — CONFIRMING is a real,
    // observable, abortable state (see SagaOrchestrationService's Javadoc).
    orchestrationService.abortSaga(saga.getId(), "ABORTED_BY_OPERATOR");

    saga = sagaInstanceRepository.findByOrderId(orderId).orElseThrow();
    assertThat(saga.getState()).isEqualTo(SagaState.COMPENSATING_PAYMENT);
    OutboxRecord refund = onlyRecordFor(orderId, "RefundPayment");
    assertThat(payloadOf(refund).get("paymentId").asText()).isEqualTo(paymentId.toString());

    orchestrationService.handlePaymentRefunded(UUID.randomUUID(), orderId, paymentId);

    saga = sagaInstanceRepository.findByOrderId(orderId).orElseThrow();
    assertThat(saga.getState()).isEqualTo(SagaState.COMPENSATING_INVENTORY);
    assertThat(onlyRecordFor(orderId, "ReleaseInventory")).isNotNull();

    orchestrationService.handleInventoryReleased(
        UUID.randomUUID(), orderId, List.of(reservationId));

    saga = sagaInstanceRepository.findByOrderId(orderId).orElseThrow();
    assertThat(saga.getState()).isEqualTo(SagaState.ABORTED);
    JsonNode payload = payloadOf(onlyRecordFor(orderId, "OrderCancelled"));
    assertThat(payload.get("compensatedSteps"))
        .extracting(JsonNode::asText)
        .containsExactlyInAnyOrder("CHARGE_PAYMENT", "RESERVE_INVENTORY");
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

  private List<OutboxRecord> commandsFor(UUID orderId, String eventType) {
    return outboxRecordRepository.findAll().stream()
        .filter(
            r ->
                r.getAggregateId().equals(orderId.toString()) && r.getEventType().equals(eventType))
        .toList();
  }

  private OutboxRecord onlyRecordFor(UUID orderId, String eventType) {
    List<OutboxRecord> records = commandsFor(orderId, eventType);
    assertThat(records).as("records of type %s for order %s", eventType, orderId).hasSize(1);
    return records.get(0);
  }

  private JsonNode payloadOf(OutboxRecord record) {
    return objectMapper.valueToTree(record.getPayload()).get("payload");
  }
}
