package com.conveyor.verifier.invariants;

import com.conveyor.verifier.config.ServiceClients;
import com.conveyor.verifier.config.ServiceDatabases;
import com.conveyor.verifier.domain.CheckMode;
import com.conveyor.verifier.domain.CheckOutcome;
import com.conveyor.verifier.domain.InvariantClass;
import com.conveyor.verifier.domain.InvariantId;
import com.conveyor.verifier.domain.Violation;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * ARCHITECTURE.md §13 — <b>the headline.</b> Every {@code CONFIRMED} order has, for each line item,
 * a {@code COMMITTED} reservation of the matching quantity (BUG-0021 closed the real gap that made
 * this unimplementable: nothing ever set {@code COMMITTED} before Phase 10).
 *
 * <p>Spans two databases with no shared connection (order-service's and inventory-service's, per
 * ARCHITECTURE.md §4's database-per-service rule) — inside-out correlates them in application code
 * exactly as an outside-in checker would have to over two REST calls, just without the network hop.
 */
@Component
public class InvOrd01ConfirmedHasCommittedReservationInvariant implements Invariant {

  @Override
  public InvariantId id() {
    return InvariantId.INV_ORD_01;
  }

  @Override
  public InvariantClass invariantClass() {
    return InvariantClass.SAFETY;
  }

  @Override
  public CheckOutcome checkInsideOut(ServiceDatabases db) {
    List<Map<String, Object>> orders =
        db.get("order").queryForList("select id from orders where status = 'CONFIRMED'");
    if (orders.isEmpty()) {
      return CheckOutcome.clean(id(), CheckMode.INSIDE_OUT);
    }
    List<Map<String, Object>> items =
        db.get("order")
            .queryForList(
                "select order_id, sku, quantity from order_items where order_id in "
                    + "(select id from orders where status = 'CONFIRMED')");
    Map<String, Long> committed = new HashMap<>();
    for (Map<String, Object> row :
        db.get("inventory")
            .queryForList(
                "select order_id, sku, quantity from reservations where status = 'COMMITTED'")) {
      committed.merge(
          row.get("order_id") + ":" + row.get("sku"),
          ((Number) row.get("quantity")).longValue(),
          Long::sum);
    }

    List<Violation> violations = new ArrayList<>();
    for (Map<String, Object> item : items) {
      String key = item.get("order_id") + ":" + item.get("sku");
      long expected = ((Number) item.get("quantity")).longValue();
      long actual = committed.getOrDefault(key, 0L);
      if (actual != expected) {
        violations.add(
            new Violation(
                id(),
                item.get("order_id").toString(),
                "sku=%s expectedCommitted=%d actualCommitted=%d"
                    .formatted(item.get("sku"), expected, actual)));
      }
    }
    return violations.isEmpty()
        ? CheckOutcome.clean(id(), CheckMode.INSIDE_OUT)
        : CheckOutcome.violated(id(), CheckMode.INSIDE_OUT, violations);
  }

  @Override
  public CheckOutcome checkOutsideIn(ServiceClients clients) {
    List<JsonNode> confirmedOrders =
        clients.getAllPages("order", "/orders").stream()
            .filter(o -> "CONFIRMED".equals(o.path("status").asText()))
            .toList();
    List<Violation> violations = new ArrayList<>();
    for (JsonNode order : confirmedOrders) {
      String orderId = order.path("orderId").asText();
      for (JsonNode item : order.path("items")) {
        String sku = item.path("sku").asText();
        long expected = item.path("quantity").asLong();
        long actual =
            clients.getArray("inventory", "/inventory/" + sku + "/reservations").stream()
                .filter(r -> "COMMITTED".equals(r.path("status").asText()))
                .filter(r -> orderId.equals(r.path("orderId").asText()))
                .mapToLong(r -> r.path("quantity").asLong())
                .sum();
        if (actual != expected) {
          violations.add(
              new Violation(
                  id(),
                  orderId,
                  "sku=%s expectedCommitted=%d actualCommitted=%d"
                      .formatted(sku, expected, actual)));
        }
      }
    }
    return violations.isEmpty()
        ? CheckOutcome.clean(id(), CheckMode.OUTSIDE_IN)
        : CheckOutcome.violated(id(), CheckMode.OUTSIDE_IN, violations);
  }
}
