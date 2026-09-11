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
 * ARCHITECTURE.md §13 — every {@code CONFIRMED} order has exactly one {@code shipments} row. No
 * {@code CANCELLED} order has one.
 */
@Component
public class InvDsp01ConfirmedHasShipmentInvariant implements Invariant {

  @Override
  public InvariantId id() {
    return InvariantId.INV_DSP_01;
  }

  @Override
  public InvariantClass invariantClass() {
    return InvariantClass.SAFETY;
  }

  @Override
  public CheckOutcome checkInsideOut(ServiceDatabases db) {
    List<Map<String, Object>> orders =
        db.get("order")
            .queryForList(
                "select id, status from orders where status in ('CONFIRMED','CANCELLED')");
    if (orders.isEmpty()) {
      return CheckOutcome.clean(id(), CheckMode.INSIDE_OUT);
    }
    Map<String, Long> shipmentCounts = new HashMap<>();
    for (Map<String, Object> row :
        db.get("dispatch")
            .queryForList("select order_id, count(*) as n from shipments group by order_id")) {
      shipmentCounts.put(row.get("order_id").toString(), ((Number) row.get("n")).longValue());
    }

    List<Violation> violations = new ArrayList<>();
    for (Map<String, Object> order : orders) {
      String orderId = order.get("id").toString();
      long count = shipmentCounts.getOrDefault(orderId, 0L);
      String status = (String) order.get("status");
      if ("CONFIRMED".equals(status) && count != 1) {
        violations.add(
            new Violation(id(), orderId, "CONFIRMED order has %d shipments".formatted(count)));
      } else if ("CANCELLED".equals(status) && count != 0) {
        violations.add(
            new Violation(id(), orderId, "CANCELLED order has %d shipments".formatted(count)));
      }
    }
    return violations.isEmpty()
        ? CheckOutcome.clean(id(), CheckMode.INSIDE_OUT)
        : CheckOutcome.violated(id(), CheckMode.INSIDE_OUT, violations);
  }

  @Override
  public CheckOutcome checkOutsideIn(ServiceClients clients) {
    List<Violation> violations = new ArrayList<>();
    for (JsonNode order : clients.getAllPages("order", "/orders")) {
      String status = order.path("status").asText();
      if (!status.equals("CONFIRMED") && !status.equals("CANCELLED")) {
        continue;
      }
      String orderId = order.path("orderId").asText();
      JsonNode shipment = clients.getOrNull("dispatch", "/shipments/" + orderId);
      boolean exists = shipment != null;
      if (status.equals("CONFIRMED") && !exists) {
        violations.add(new Violation(id(), orderId, "CONFIRMED order has no shipment"));
      } else if (status.equals("CANCELLED") && exists) {
        violations.add(new Violation(id(), orderId, "CANCELLED order has a shipment"));
      }
    }
    return violations.isEmpty()
        ? CheckOutcome.clean(id(), CheckMode.OUTSIDE_IN)
        : CheckOutcome.violated(id(), CheckMode.OUTSIDE_IN, violations);
  }
}
