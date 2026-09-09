package com.conveyor.saga.domain;

/** ARCHITECTURE.md §5.2. */
public enum StepStatus {
  STARTED,
  SUCCEEDED,
  FAILED,
  TIMED_OUT
}
