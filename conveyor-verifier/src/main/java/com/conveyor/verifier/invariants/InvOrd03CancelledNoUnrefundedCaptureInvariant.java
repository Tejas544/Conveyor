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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * ARCHITECTURE.md §13 — no {@code CANCELLED} order has a payment in {@code CAPTURED} without a
 * matching {@code REFUNDED}. Payment-service's own {@code payments.order_id unique} constraint
 * means one row per order carries the current status, so "captured without a matching refund" is
 * exactly "still {@code CAPTURED}".
 */
@Component
public class InvOrd03CancelledNoUnrefundedCaptureInvariant implements Invariant {

  @Override
  public InvariantId id() {
    return InvariantId.INV_ORD_03;
  }

  @Override
  public InvariantClass invariantClass() {
    return InvariantClass.SAFETY;
  }

  @Override
  public CheckOutcome checkInsideOut(ServiceDatabases db) {
    Set<String> cancelledIds =
        db.get("order").queryForList("select id from orders where status = 'CANCELLED'").stream()
            .map(row -> row.get("id").toString())
            .collect(Collectors.toSet());
    if (cancelledIds.isEmpty()) {
      return CheckOutcome.clean(id(), CheckMode.INSIDE_OUT);
    }
    List<Map<String, Object>> captured =
        db.get("payment").queryForList("select order_id from payments where status = 'CAPTURED'");

    List<Violation> violations = new ArrayList<>();
    for (Map<String, Object> row : captured) {
      String orderId = row.get("order_id").toString();
      if (cancelledIds.contains(orderId)) {
        violations.add(new Violation(id(), orderId, "payment still CAPTURED on a CANCELLED order"));
      }
    }
    return violations.isEmpty()
        ? CheckOutcome.clean(id(), CheckMode.INSIDE_OUT)
        : CheckOutcome.violated(id(), CheckMode.INSIDE_OUT, violations);
  }

  @Override
  public CheckOutcome checkOutsideIn(ServiceClients clients) {
    List<JsonNode> cancelledOrders =
        clients.getAllPages("order", "/orders").stream()
            .filter(o -> "CANCELLED".equals(o.path("status").asText()))
            .toList();
    List<Violation> violations = new ArrayList<>();
    for (JsonNode order : cancelledOrders) {
      String orderId = order.path("orderId").asText();
      var payment = clients.getOrNull("payment", "/payments/" + orderId);
      if (payment != null && "CAPTURED".equals(payment.path("status").asText())) {
        violations.add(new Violation(id(), orderId, "payment still CAPTURED on a CANCELLED order"));
      }
    }
    return violations.isEmpty()
        ? CheckOutcome.clean(id(), CheckMode.OUTSIDE_IN)
        : CheckOutcome.violated(id(), CheckMode.OUTSIDE_IN, violations);
  }
}
