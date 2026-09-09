package com.conveyor.saga.domain;

/** ARCHITECTURE.md §7.2 — the saga-orchestrator's authoritative state machine. */
public enum SagaState {
  STARTED,
  RESERVING_INVENTORY,
  CHARGING_PAYMENT,
  CONFIRMING,
  COMPLETED,
  ABORTING,
  ABORTED,
  COMPENSATING_INVENTORY,
  COMPENSATING_PAYMENT,
  NEEDS_INTERVENTION
}
