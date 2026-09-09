package com.conveyor.saga.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.saga.domain.SagaInstance;
import com.conveyor.saga.domain.SagaState;
import com.conveyor.saga.domain.SagaStep;
import com.conveyor.saga.domain.StepDirection;
import com.conveyor.saga.domain.StepStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

class SagaRepositoryIntegrationTest extends AbstractIntegrationTest {

  @Autowired private SagaInstanceRepository sagaInstanceRepository;
  @Autowired private SagaStepRepository sagaStepRepository;

  @Test
  @Transactional
  void savesAndFindsByOrderId() {
    UUID orderId = UUID.randomUUID();
    sagaInstanceRepository.saveAndFlush(
        new SagaInstance(UUID.randomUUID(), orderId, "ORDER_FULFILLMENT", SagaState.STARTED));

    assertThat(sagaInstanceRepository.findByOrderId(orderId)).isPresent();
  }

  @Test
  @Transactional
  void orderIdIsUnique() {
    UUID orderId = UUID.randomUUID();
    sagaInstanceRepository.saveAndFlush(
        new SagaInstance(UUID.randomUUID(), orderId, "ORDER_FULFILLMENT", SagaState.STARTED));

    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            sagaInstanceRepository.saveAndFlush(
                new SagaInstance(
                    UUID.randomUUID(), orderId, "ORDER_FULFILLMENT", SagaState.STARTED)));
  }

  @Test
  @Transactional
  void stepLogIsAppendOnlyAndOrderedBySeq() {
    SagaInstance saga =
        sagaInstanceRepository.saveAndFlush(
            new SagaInstance(
                UUID.randomUUID(), UUID.randomUUID(), "ORDER_FULFILLMENT", SagaState.STARTED));

    sagaStepRepository.saveAndFlush(
        new SagaStep(
            saga.getId(),
            1,
            "RESERVE_INVENTORY",
            StepDirection.FORWARD,
            StepStatus.STARTED,
            null,
            null));
    sagaStepRepository.saveAndFlush(
        new SagaStep(
            saga.getId(),
            2,
            "RESERVE_INVENTORY",
            StepDirection.FORWARD,
            StepStatus.SUCCEEDED,
            UUID.randomUUID(),
            Map.of("reservationIds", List.of(UUID.randomUUID().toString()))));

    List<SagaStep> steps = sagaStepRepository.findBySagaIdOrderBySeqAsc(saga.getId());
    assertThat(steps).hasSize(2);
    assertThat(steps.get(0).getStatus()).isEqualTo(StepStatus.STARTED);
    assertThat(steps.get(1).getStatus()).isEqualTo(StepStatus.SUCCEEDED);
  }

  @Test
  @Transactional
  void sagaSeqIsUnique() {
    SagaInstance saga =
        sagaInstanceRepository.saveAndFlush(
            new SagaInstance(
                UUID.randomUUID(), UUID.randomUUID(), "ORDER_FULFILLMENT", SagaState.STARTED));
    sagaStepRepository.saveAndFlush(
        new SagaStep(
            saga.getId(),
            1,
            "RESERVE_INVENTORY",
            StepDirection.FORWARD,
            StepStatus.STARTED,
            null,
            null));

    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            sagaStepRepository.saveAndFlush(
                new SagaStep(
                    saga.getId(),
                    1,
                    "CHARGE_PAYMENT",
                    StepDirection.FORWARD,
                    StepStatus.STARTED,
                    null,
                    null)));
  }
}
