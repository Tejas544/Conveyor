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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * ARCHITECTURE.md §13 — no {@code CANCELLED} order has a {@code HELD} reservation: compensation
 * released what it held. ARCHITECTURE.md §13's own illustrative example for why inside-out matters
 * ("invisible to {@code GET /orders/{id}}") turns out to still be reachable outside-in <i>if</i>
 * the checker also calls inventory-service's reservations endpoint — implemented here for an honest
 * comparison rather than a strawman outside-in checker that only ever calls one service.
 */
@Component
public class InvOrd02CancelledHasNoHeldReservationInvariant implements Invariant {

  @Override
  public InvariantId id() {
    return InvariantId.INV_ORD_02;
  }

  @Override
  public InvariantClass invariantClass() {
    return InvariantClass.SAFETY;
  }

  @Override
  public CheckOutcome checkInsideOut(ServiceDatabases db) {
    List<Map<String, Object>> cancelled =
        db.get("order").queryForList("select id from orders where status = 'CANCELLED'");
    Set<String> cancelledIds =
        cancelled.stream()
            .map(row -> row.get("id").toString())
            .collect(java.util.stream.Collectors.toSet());
    if (cancelledIds.isEmpty()) {
      return CheckOutcome.clean(id(), CheckMode.INSIDE_OUT);
    }
    List<Map<String, Object>> held =
        db.get("inventory")
            .queryForList("select order_id, sku from reservations where status = 'HELD'");

    List<Violation> violations = new ArrayList<>();
    for (Map<String, Object> row : held) {
      String orderId = row.get("order_id").toString();
      if (cancelledIds.contains(orderId)) {
        violations.add(new Violation(id(), orderId, "sku=%s still HELD".formatted(row.get("sku"))));
      }
    }
    return violations.isEmpty()
        ? CheckOutcome.clean(id(), CheckMode.INSIDE_OUT)
        : CheckOutcome.violated(id(), CheckMode.INSIDE_OUT, violations);
  }

  @Override
  public CheckOutcome checkOutsideIn(ServiceClients clients) {
    Set<String> cancelledIds = new HashSet<>();
    for (JsonNode order : clients.getAllPages("order", "/orders")) {
      if ("CANCELLED".equals(order.path("status").asText())) {
        cancelledIds.add(order.path("orderId").asText());
      }
    }
    if (cancelledIds.isEmpty()) {
      return CheckOutcome.clean(id(), CheckMode.OUTSIDE_IN);
    }

    List<Violation> violations = new ArrayList<>();
    for (JsonNode item : clients.getAllPages("inventory", "/inventory")) {
      String sku = item.path("sku").asText();
      for (JsonNode reservation :
          clients.getArray("inventory", "/inventory/" + sku + "/reservations")) {
        if (!"HELD".equals(reservation.path("status").asText())) {
          continue;
        }
        String orderId = reservation.path("orderId").asText();
        if (cancelledIds.contains(orderId)) {
          violations.add(new Violation(id(), orderId, "sku=%s still HELD".formatted(sku)));
        }
      }
    }
    return violations.isEmpty()
        ? CheckOutcome.clean(id(), CheckMode.OUTSIDE_IN)
        : CheckOutcome.violated(id(), CheckMode.OUTSIDE_IN, violations);
  }
}
