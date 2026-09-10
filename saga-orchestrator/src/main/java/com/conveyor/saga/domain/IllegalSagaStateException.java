package com.conveyor.saga.domain;

import java.util.UUID;

/**
 * Thrown when an operation (retry, abort) is requested against a saga not currently in a state it
 * applies to.
 */
public class IllegalSagaStateException extends RuntimeException {

  public IllegalSagaStateException(UUID sagaId, SagaState actual, String requiredFor) {
    super("Saga " + sagaId + " is in state " + actual + ", cannot " + requiredFor);
  }
}
