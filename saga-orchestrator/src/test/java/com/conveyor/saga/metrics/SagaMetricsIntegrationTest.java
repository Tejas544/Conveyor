package com.conveyor.saga.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.contracts.events.InventoryItemPayload;
import com.conveyor.contracts.events.OrderItemPayload;
import com.conveyor.saga.domain.SagaInstance;
import com.conveyor.saga.repository.SagaInstanceRepository;
import com.conveyor.saga.scheduling.SagaTimeoutSweeper;
import com.conveyor.saga.service.SagaOrchestrationService;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * PLAN.md Phase 9's exit criterion: "each custom metric is asserted present with correct labels
 * after driving a saga" — ARCHITECTURE.md §11's full instrument table, not eyeballed via
 * /actuator/prometheus. {@link #sagaActiveAndNeedsInterventionAreRegisteredEvenWithNoSagas()} is
 * separate from the rest because those two gauges are registered eagerly in {@link SagaMetrics}'s
 * constructor and don't need any saga to exist first — the other instruments are only registered
 * the first time something actually happens (Micrometer's normal behaviour), so this class drives
 * one real saga through happy path, a redelivered reply (idempotency), and an escalated timeout to
 * produce every label combination once.
 */
class SagaMetricsIntegrationTest extends AbstractIntegrationTest {

  @Autowired private SagaOrchestrationService orchestrationService;
  @Autowired private SagaInstanceRepository sagaInstanceRepository;
  @Autowired private SagaTimeoutSweeper sweeper;
  @Autowired private MeterRegistry registry;

  @Test
  void sagaActiveAndNeedsInterventionAreRegisteredEvenWithNoSagas() {
    assertThat(registry.find("conveyor_saga_active").gauge()).isNotNull();
    assertThat(registry.find("conveyor_saga_needs_intervention").gauge()).isNotNull();
  }

  @Test
  void outboxLagGaugeIsRegistered() {
    assertThat(registry.find("conveyor_outbox_lag_seconds").gauge()).isNotNull();
  }

  @Test
  void happyPathSagaProducesDurationAndTerminalAndStepMetrics() {
    UUID orderId = startSaga();
    UUID reservationId = UUID.randomUUID();

    // Redeliver the same reply twice with the same eventId to also exercise
    // conveyor_inbox_duplicates_total{consumer="saga-orchestrator"}.
    UUID reservedEventId = UUID.randomUUID();
    orchestrationService.handleInventoryReserved(
        reservedEventId,
        orderId,
        List.of(reservationId),
        List.of(new InventoryItemPayload("SKU-1", 2)));
    orchestrationService.handleInventoryReserved(
        reservedEventId,
        orderId,
        List.of(reservationId),
        List.of(new InventoryItemPayload("SKU-1", 2)));

    orchestrationService.handlePaymentCharged(
        UUID.randomUUID(), orderId, UUID.randomUUID(), new BigDecimal("20.00"), "gw_ref_1");
    SagaInstance saga = sagaInstanceRepository.findByOrderId(orderId).orElseThrow();
    orchestrationService.tryConfirm(saga.getId());

    assertThat(meterWithTags("conveyor_saga_duration_seconds", Tag.of("outcome", "COMPLETED")))
        .isNotNull();
    assertThat(meterWithTags("conveyor_saga_terminal_total", Tag.of("outcome", "COMPLETED")))
        .isNotNull();
    assertThat(
            meterWithTags(
                "conveyor_saga_step_duration_seconds",
                Tag.of("step", "RESERVE_INVENTORY"),
                Tag.of("direction", "FORWARD")))
        .isNotNull();
    assertThat(
            meterWithTags(
                "conveyor_saga_step_duration_seconds",
                Tag.of("step", "CHARGE_PAYMENT"),
                Tag.of("direction", "FORWARD")))
        .isNotNull();
    assertThat(
            meterWithTags(
                "conveyor_saga_step_duration_seconds",
                Tag.of("step", "CONFIRM_ORDER"),
                Tag.of("direction", "FORWARD")))
        .isNotNull();
    assertThat(
            meterWithTags(
                "conveyor_inbox_duplicates_total", Tag.of("consumer", "saga-orchestrator")))
        .isNotNull();
  }

  @Test
  void forwardTimeoutProducesTimeoutMetric() {
    UUID orderId = startSaga();
    SagaInstance saga = sagaInstanceRepository.findByOrderId(orderId).orElseThrow();
    saga.setDeadlineAt(Instant.now().minusSeconds(5));
    sagaInstanceRepository.saveAndFlush(saga);

    sweeper.sweepOnce();

    assertThat(meterWithTags("conveyor_saga_timeouts_total", Tag.of("step", "RESERVE_INVENTORY")))
        .isNotNull();
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

  private Meter meterWithTags(String name, Tag... tags) {
    return registry.find(name).tags(List.of(tags)).meter();
  }
}
