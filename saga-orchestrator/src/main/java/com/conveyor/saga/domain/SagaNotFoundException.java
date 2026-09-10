package com.conveyor.saga.domain;

import java.util.UUID;

public class SagaNotFoundException extends RuntimeException {

  private SagaNotFoundException(String message) {
    super(message);
  }

  public static SagaNotFoundException forOrderId(UUID orderId) {
    return new SagaNotFoundException("No saga found for order " + orderId);
  }

  public static SagaNotFoundException forSagaId(UUID sagaId) {
    return new SagaNotFoundException("No saga found with id " + sagaId);
  }
}
