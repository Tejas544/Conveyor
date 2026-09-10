package com.conveyor.inventory.domain;

/** Thrown when {@code on_hand + delta} would drop below {@code reserved} (ADR-9's constraint). */
public class StockAdjustmentRejectedException extends RuntimeException {

  public StockAdjustmentRejectedException(String sku, int delta) {
    super("Adjustment of " + delta + " to " + sku + " would drop on-hand below reserved stock");
  }
}
