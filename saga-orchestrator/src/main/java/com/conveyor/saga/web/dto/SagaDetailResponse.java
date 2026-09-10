package com.conveyor.saga.web.dto;

import com.conveyor.saga.domain.SagaInstance;
import com.conveyor.saga.domain.SagaStep;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SagaDetailResponse(
    UUID sagaId,
    UUID orderId,
    String definition,
    String state,
    String currentStep,
    String failureReason,
    Instant deadlineAt,
    int attempt,
    Instant createdAt,
    Instant updatedAt,
    List<SagaStepResponse> steps) {

  public static SagaDetailResponse from(SagaInstance saga, List<SagaStep> steps) {
    return new SagaDetailResponse(
        saga.getId(),
        saga.getOrderId(),
        saga.getDefinition(),
        saga.getState().name(),
        saga.getCurrentStep(),
        saga.getFailureReason(),
        saga.getDeadlineAt(),
        saga.getAttempt(),
        saga.getCreatedAt(),
        saga.getUpdatedAt(),
        steps.stream().map(SagaStepResponse::from).toList());
  }
}
