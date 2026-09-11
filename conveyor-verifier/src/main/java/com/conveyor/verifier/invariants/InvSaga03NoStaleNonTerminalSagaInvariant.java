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
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * ARCHITECTURE.md §13 — liveness: no saga has been non-terminal for longer than {@code maxSagaAge}
 * (default 5 min).
 */
@Component
public class InvSaga03NoStaleNonTerminalSagaInvariant implements Invariant {

  private final VerifierProperties properties;

  public InvSaga03NoStaleNonTerminalSagaInvariant(VerifierProperties properties) {
    this.properties = properties;
  }

  @Override
  public InvariantId id() {
    return InvariantId.INV_SAGA_03;
  }

  @Override
  public InvariantClass invariantClass() {
    return InvariantClass.LIVENESS;
  }

  @Override
  public CheckOutcome checkInsideOut(ServiceDatabases db) {
    Instant cutoff = Instant.now().minus(properties.maxSagaAge());
    List<Map<String, Object>> rows =
        db.get("saga")
            .queryForList(
                "select id, created_at from saga_instances where state not in "
                    + "('COMPLETED','ABORTED','NEEDS_INTERVENTION') and created_at < ?",
                Timestamp.from(cutoff));
    List<Violation> violations =
        rows.stream()
            .map(
                row ->
                    new Violation(
                        id(),
                        row.get("id").toString(),
                        "createdAt=%s".formatted(row.get("created_at"))))
            .toList();
    return violations.isEmpty()
        ? CheckOutcome.clean(id(), CheckMode.INSIDE_OUT)
        : CheckOutcome.violated(id(), CheckMode.INSIDE_OUT, violations);
  }

  @Override
  public CheckOutcome checkOutsideIn(ServiceClients clients) {
    Instant cutoff = Instant.now().minus(properties.maxSagaAge());
    List<Violation> violations = new java.util.ArrayList<>();
    for (JsonNode summary : clients.getArray("saga", "/sagas")) {
      String state = summary.path("state").asText();
      if (state.equals("COMPLETED")
          || state.equals("ABORTED")
          || state.equals("NEEDS_INTERVENTION")) {
        continue;
      }
      String orderId = summary.path("orderId").asText();
      JsonNode detail = clients.getOrNull("saga", "/sagas/" + orderId);
      if (detail == null) {
        continue;
      }
      Instant createdAt = Instant.parse(detail.path("createdAt").asText());
      if (createdAt.isBefore(cutoff)) {
        violations.add(
            new Violation(
                id(), detail.path("sagaId").asText(), "createdAt=%s".formatted(createdAt)));
      }
    }
    return violations.isEmpty()
        ? CheckOutcome.clean(id(), CheckMode.OUTSIDE_IN)
        : CheckOutcome.violated(id(), CheckMode.OUTSIDE_IN, violations);
  }
}
