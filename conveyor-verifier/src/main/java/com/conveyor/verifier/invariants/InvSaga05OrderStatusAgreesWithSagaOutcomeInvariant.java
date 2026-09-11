package com.conveyor.verifier.invariants;

import com.conveyor.verifier.config.ServiceClients;
import com.conveyor.verifier.config.ServiceDatabases;
import com.conveyor.verifier.config.VerifierProperties;
import com.conveyor.verifier.domain.CheckMode;
import com.conveyor.verifier.domain.CheckOutcome;
import com.conveyor.verifier.domain.InvariantClass;
import com.conveyor.verifier.domain.InvariantId;
import com.conveyor.verifier.domain.Violation;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * ARCHITECTURE.md §13 — for every saga terminal for more than {@code terminalGrace} (default 30s),
 * {@code orders.status} agrees with the saga outcome: {@code COMPLETED} → {@code CONFIRMED}, {@code
 * ABORTED} → {@code CANCELLED}. The projection (order-service's {@code
 * SagaEventProjectionListener}) catches up.
 */
@Component
public class InvSaga05OrderStatusAgreesWithSagaOutcomeInvariant implements Invariant {

  private final VerifierProperties properties;

  public InvSaga05OrderStatusAgreesWithSagaOutcomeInvariant(VerifierProperties properties) {
    this.properties = properties;
  }

  @Override
  public InvariantId id() {
    return InvariantId.INV_SAGA_05;
  }

  @Override
  public InvariantClass invariantClass() {
    return InvariantClass.CONVERGENCE;
  }

  private static String expectedOrderStatus(String sagaState) {
    return switch (sagaState) {
      case "COMPLETED" -> "CONFIRMED";
      case "ABORTED" -> "CANCELLED";
      default -> null;
    };
  }

  @Override
  public CheckOutcome checkInsideOut(ServiceDatabases db) {
    Instant cutoff = Instant.now().minus(properties.terminalGrace());
    List<Map<String, Object>> sagas =
        db.get("saga")
            .queryForList(
                "select order_id, state from saga_instances "
                    + "where state in ('COMPLETED','ABORTED') and updated_at < ?",
                Timestamp.from(cutoff));
    if (sagas.isEmpty()) {
      return CheckOutcome.clean(id(), CheckMode.INSIDE_OUT);
    }
    Map<String, String> orderStatuses = new HashMap<>();
    for (Map<String, Object> row : db.get("order").queryForList("select id, status from orders")) {
      orderStatuses.put(row.get("id").toString(), (String) row.get("status"));
    }

    List<Violation> violations = new ArrayList<>();
    for (Map<String, Object> saga : sagas) {
      String orderId = saga.get("order_id").toString();
      String expected = expectedOrderStatus((String) saga.get("state"));
      String actual = orderStatuses.get(orderId);
      if (!expected.equals(actual)) {
        violations.add(
            new Violation(
                id(),
                orderId,
                "sagaState=%s expectedOrderStatus=%s actualOrderStatus=%s"
                    .formatted(saga.get("state"), expected, actual)));
      }
    }
    return violations.isEmpty()
        ? CheckOutcome.clean(id(), CheckMode.INSIDE_OUT)
        : CheckOutcome.violated(id(), CheckMode.INSIDE_OUT, violations);
  }

  @Override
  public CheckOutcome checkOutsideIn(ServiceClients clients) {
    Instant cutoff = Instant.now().minus(properties.terminalGrace());
    List<Violation> violations = new ArrayList<>();
    for (JsonNode summary : clients.getArray("saga", "/sagas")) {
      String state = summary.path("state").asText();
      String expected = expectedOrderStatus(state);
      if (expected == null) {
        continue;
      }
      String orderId = summary.path("orderId").asText();
      JsonNode detail = clients.getOrNull("saga", "/sagas/" + orderId);
      if (detail == null || Instant.parse(detail.path("updatedAt").asText()).isAfter(cutoff)) {
        continue;
      }
      JsonNode order = clients.getOrNull("order", "/orders/" + orderId);
      String actual = order == null ? null : order.path("status").asText();
      if (!expected.equals(actual)) {
        violations.add(
            new Violation(
                id(),
                orderId,
                "sagaState=%s expectedOrderStatus=%s actualOrderStatus=%s"
                    .formatted(state, expected, actual)));
      }
    }
    return violations.isEmpty()
        ? CheckOutcome.clean(id(), CheckMode.OUTSIDE_IN)
        : CheckOutcome.violated(id(), CheckMode.OUTSIDE_IN, violations);
  }
}
