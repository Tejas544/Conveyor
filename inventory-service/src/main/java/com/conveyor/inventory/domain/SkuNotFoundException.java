package com.conveyor.inventory.domain;

public class SkuNotFoundException extends RuntimeException {

  public SkuNotFoundException(String sku) {
    super("SKU not found: " + sku);
  }
}
