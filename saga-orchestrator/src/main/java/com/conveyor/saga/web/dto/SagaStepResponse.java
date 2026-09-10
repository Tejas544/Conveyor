package com.conveyor.saga.web.dto;

import com.conveyor.saga.domain.SagaStep;
import java.time.Instant;
import java.util.Map;

public record SagaStepResponse(
    int seq,
    String step,
    String direction,
    String status,
    Instant occurredAt,
    Map<String, Object> detail) {

  public static SagaStepResponse from(SagaStep step) {
    return new SagaStepResponse(
        step.getSeq(),
        step.getStep(),
        step.getDirection().name(),
        step.getStatus().name(),
        step.getOccurredAt(),
        step.getDetail());
  }
}
