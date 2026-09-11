package com.conveyor.verifier.domain;

/** ARCHITECTURE.md §13 / docs/INVARIANTS.md — the frozen catalogue of 15 IDs. */
public enum InvariantId {
  INV_INV_01("INV-INV-01"),
  INV_INV_02("INV-INV-02"),
  INV_INV_03("INV-INV-03"),
  INV_ORD_01("INV-ORD-01"),
  INV_ORD_02("INV-ORD-02"),
  INV_ORD_03("INV-ORD-03"),
  INV_ORD_04("INV-ORD-04"),
  INV_PAY_01("INV-PAY-01"),
  INV_SAGA_01("INV-SAGA-01"),
  INV_SAGA_02("INV-SAGA-02"),
  INV_SAGA_03("INV-SAGA-03"),
  INV_SAGA_04("INV-SAGA-04"),
  INV_SAGA_05("INV-SAGA-05"),
  INV_DSP_01("INV-DSP-01"),
  INV_BOX_01("INV-BOX-01");

  private final String id;

  InvariantId(String id) {
    this.id = id;
  }

  /**
   * The exact string used as the {@code invariant} metric/report tag — matches ARCHITECTURE.md §13.
   */
  public String tag() {
    return id;
  }

  @Override
  public String toString() {
    return id;
  }
}
