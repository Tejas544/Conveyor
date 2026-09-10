package com.conveyor.saga.domain;

/**
 * ARCHITECTURE.md §7.3 — the one saga definition this engine drives ({@code ORDER_FULFILLMENT}): an
 * ordered list of forward steps, each with a named compensation. Kept as plain data rather than a
 * generic interpreter (PLAN.md's own risk register: "one definition, no dynamic branching, no
 * nested sagas") — the step names below are what {@code saga_steps.step} records, and what {@link
 * com.conveyor.saga.service.SagaOrchestrationService#abortFrom} reads back out of the log to derive
 * which compensations are owed, rather than assuming a hardcoded set (§8.2).
 */
public final class SagaSteps {

  public static final String DEFINITION_ORDER_FULFILLMENT = "ORDER_FULFILLMENT";

  public static final String RESERVE_INVENTORY = "RESERVE_INVENTORY";
  public static final String RELEASE_INVENTORY = "RELEASE_INVENTORY";
  public static final String CHARGE_PAYMENT = "CHARGE_PAYMENT";
  public static final String REFUND_PAYMENT = "REFUND_PAYMENT";
  public static final String CONFIRM_ORDER = "CONFIRM_ORDER";

  /** The forward step's compensating counterpart, or {@code null} if it has none (the pivot). */
  public static String compensationFor(String forwardStep) {
    return switch (forwardStep) {
      case RESERVE_INVENTORY -> RELEASE_INVENTORY;
      case CHARGE_PAYMENT -> REFUND_PAYMENT;
      default -> null;
    };
  }

  private SagaSteps() {}
}
