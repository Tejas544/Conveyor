package com.conveyor.order.domain;

/**
 * Thrown by {@link Order#transitionTo(OrderStatus)} when the move isn't on the ARCHITECTURE.md §7.1
 * diagram.
 */
public class IllegalOrderTransitionException extends RuntimeException {

  public IllegalOrderTransitionException(OrderStatus from, OrderStatus to) {
    super("Illegal order state transition: " + from + " -> " + to);
  }
}
