package com.conveyor.saga.web.dto;

import com.conveyor.saga.domain.SagaInstance;
import java.time.Instant;
import java.util.UUID;

public record SagaSummaryResponse(
    UUID sagaId, UUID orderId, String state, String currentStep, Instant deadlineAt, int attempt) {

  public static SagaSummaryResponse from(SagaInstance saga) {
    return new SagaSummaryResponse(
        saga.getId(),
        saga.getOrderId(),
        saga.getState().name(),
        saga.getCurrentStep(),
        saga.getDeadlineAt(),
        saga.getAttempt());
  }
}
