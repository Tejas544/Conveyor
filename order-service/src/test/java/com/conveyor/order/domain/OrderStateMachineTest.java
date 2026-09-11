package com.conveyor.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * PLAN.md Phase 3: unit tests for the order state machine (ARCHITECTURE.md §7.1) — every legal
 * transition succeeds, and every one of the remaining pairs (illegal by omission from the diagram)
 * is rejected. Runs with no Spring context: this is pure domain logic.
 */
class OrderStateMachineTest {

  private static final Map<OrderStatus, Set<OrderStatus>> LEGAL =
      Map.of(
          OrderStatus.PLACED,
              Set.of(OrderStatus.INVENTORY_RESERVED, OrderStatus.COMPENSATING, OrderStatus.CANCELLED),
          OrderStatus.INVENTORY_RESERVED,
              Set.of(OrderStatus.PAYMENT_CHARGED, OrderStatus.COMPENSATING, OrderStatus.CANCELLED),
          OrderStatus.PAYMENT_CHARGED,
              Set.of(OrderStatus.CONFIRMED, OrderStatus.COMPENSATING, OrderStatus.CANCELLED),
          OrderStatus.CONFIRMED, Set.of(),
          OrderStatus.COMPENSATING, Set.of(OrderStatus.CANCELLED),
          OrderStatus.CANCELLED, Set.of());

  private Order newOrderAt(OrderStatus status) {
    Order order =
        new Order(
            UUID.randomUUID(),
            UUID.randomUUID(),
            OrderStatus.PLACED,
            new BigDecimal("10.00"),
            "USD",
            Map.of(
                "line1", "1 Test St", "city", "Testville", "postalCode", "00000", "country", "IN"),
            null);
    // Drive it to `status` via a known-legal path so the fixture itself never trips the guard.
    for (OrderStatus step : pathTo(status)) {
      order.transitionTo(step);
    }
    return order;
  }

  /** A concrete legal path from PLACED to each status, used only to build test fixtures. */
  private static java.util.List<OrderStatus> pathTo(OrderStatus target) {
    return switch (target) {
      case PLACED -> java.util.List.of();
      case INVENTORY_RESERVED -> java.util.List.of(OrderStatus.INVENTORY_RESERVED);
      case PAYMENT_CHARGED ->
          java.util.List.of(OrderStatus.INVENTORY_RESERVED, OrderStatus.PAYMENT_CHARGED);
      case CONFIRMED ->
          java.util.List.of(
              OrderStatus.INVENTORY_RESERVED, OrderStatus.PAYMENT_CHARGED, OrderStatus.CONFIRMED);
      case COMPENSATING -> java.util.List.of(OrderStatus.COMPENSATING);
      case CANCELLED -> java.util.List.of(OrderStatus.COMPENSATING, OrderStatus.CANCELLED);
    };
  }

  @ParameterizedTest
  @EnumSource(OrderStatus.class)
  void everyPairIsExactlyTheOneTheDiagramAllows(OrderStatus from) {
    for (OrderStatus to : EnumSet.allOf(OrderStatus.class)) {
      boolean expectedLegal = LEGAL.get(from).contains(to);

      Order order = newOrderAt(from);
      if (expectedLegal) {
        order.transitionTo(to);
        assertThat(order.getStatus()).as("%s -> %s should succeed", from, to).isEqualTo(to);
      } else {
        assertThatThrownBy(() -> order.transitionTo(to))
            .as("%s -> %s should be rejected", from, to)
            .isInstanceOf(IllegalOrderTransitionException.class);
      }
    }
  }

  @Test
  void terminalStatesAcceptNoTransitionAtAll() {
    Order confirmed = newOrderAt(OrderStatus.CONFIRMED);
    Order cancelled = newOrderAt(OrderStatus.CANCELLED);

    for (OrderStatus target : OrderStatus.values()) {
      assertThatThrownBy(() -> confirmed.transitionTo(target))
          .isInstanceOf(IllegalOrderTransitionException.class);
      assertThatThrownBy(() -> cancelled.transitionTo(target))
          .isInstanceOf(IllegalOrderTransitionException.class);
    }
  }
}
