package com.conveyor.order.domain;

import java.util.UUID;

public class OrderNotFoundException extends RuntimeException {

  public OrderNotFoundException(UUID orderId) {
    super("Order not found: " + orderId);
  }
}
