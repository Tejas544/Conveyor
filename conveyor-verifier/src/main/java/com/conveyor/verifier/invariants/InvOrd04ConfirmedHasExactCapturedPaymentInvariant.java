package com.conveyor.verifier.invariants;

import com.conveyor.verifier.config.ServiceClients;
import com.conveyor.verifier.config.ServiceDatabases;
import com.conveyor.verifier.domain.CheckMode;
import com.conveyor.verifier.domain.CheckOutcome;
import com.conveyor.verifier.domain.InvariantClass;
import com.conveyor.verifier.domain.InvariantId;
import com.conveyor.verifier.domain.Violation;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * ARCHITECTURE.md §13 — every {@code CONFIRMED} order has exactly one payment in {@code CAPTURED}
 * for exactly {@code orders.total_amount}.
 */
@Component
public class InvOrd04ConfirmedHasExactCapturedPaymentInvariant implements Invariant {

  @Override
  public InvariantId id() {
    return InvariantId.INV_ORD_04;
  }

  @Override
  public InvariantClass invariantClass() {
    return InvariantClass.SAFETY;
  }

  @Override
  public CheckOutcome checkInsideOut(ServiceDatabases db) {
    List<Map<String, Object>> orders =
        db.get("order")
            .queryForList("select id, total_amount from orders where status = 'CONFIRMED'");
    if (orders.isEmpty()) {
      return CheckOutcome.clean(id(), CheckMode.INSIDE_OUT);
    }
    Map<String, BigDecimal> captured = new HashMap<>();
    for (Map<String, Object> row :
        db.get("payment")
            .queryForList("select order_id, amount from payments where status = 'CAPTURED'")) {
      captured.put(row.get("order_id").toString(), (BigDecimal) row.get("amount"));
    }

    List<Violation> violations = new ArrayList<>();
    for (Map<String, Object> order : orders) {
      String orderId = order.get("id").toString();
      BigDecimal expected = (BigDecimal) order.get("total_amount");
      BigDecimal actual = captured.get(orderId);
      if (actual == null) {
        violations.add(new Violation(id(), orderId, "no CAPTURED payment found"));
      } else if (actual.compareTo(expected) != 0) {
        violations.add(
            new Violation(id(), orderId, "expected=%s actual=%s".formatted(expected, actual)));
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
      BigDecimal expected = new BigDecimal(order.path("totalAmount").asText());
      JsonNode payment = clients.getOrNull("payment", "/payments/" + orderId);
      if (payment == null || !"CAPTURED".equals(payment.path("status").asText())) {
        violations.add(new Violation(id(), orderId, "no CAPTURED payment found"));
        continue;
      }
      BigDecimal actual = new BigDecimal(payment.path("amount").asText());
      if (actual.compareTo(expected) != 0) {
        violations.add(
            new Violation(id(), orderId, "expected=%s actual=%s".formatted(expected, actual)));
      }
    }
    return violations.isEmpty()
        ? CheckOutcome.clean(id(), CheckMode.OUTSIDE_IN)
        : CheckOutcome.violated(id(), CheckMode.OUTSIDE_IN, violations);
  }
}
