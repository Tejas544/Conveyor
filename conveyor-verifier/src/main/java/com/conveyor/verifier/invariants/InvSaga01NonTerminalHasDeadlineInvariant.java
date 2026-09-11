package com.conveyor.verifier.invariants;

import com.conveyor.verifier.config.ServiceClients;
import com.conveyor.verifier.config.ServiceDatabases;
import com.conveyor.verifier.domain.CheckMode;
import com.conveyor.verifier.domain.CheckOutcome;
import com.conveyor.verifier.domain.InvariantClass;
import com.conveyor.verifier.domain.InvariantId;
import com.conveyor.verifier.domain.Violation;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * ARCHITECTURE.md §13 — every non-terminal {@code saga_instances} row has a non-null {@code
 * deadline_at}. Nothing can wedge unnoticed by {@link com.conveyor.common.outbox.OutboxPoller}'s
 * sibling, {@code SagaTimeoutSweeper} — it can only sweep sagas that actually carry a deadline.
 */
@Component
public class InvSaga01NonTerminalHasDeadlineInvariant implements Invariant {

  private static final String TERMINAL_STATES = "('COMPLETED','ABORTED','NEEDS_INTERVENTION')";

  @Override
  public InvariantId id() {
    return InvariantId.INV_SAGA_01;
  }

  @Override
  public InvariantClass invariantClass() {
    return InvariantClass.SAFETY;
  }

  @Override
  public CheckOutcome checkInsideOut(ServiceDatabases db) {
    List<Map<String, Object>> rows =
        db.get("saga")
            .queryForList(
                "select id from saga_instances where state not in "
                    + TERMINAL_STATES
                    + " and deadline_at is null");
    List<Violation> violations =
        rows.stream()
            .map(row -> new Violation(id(), row.get("id").toString(), "no deadline_at"))
            .toList();
    return violations.isEmpty()
        ? CheckOutcome.clean(id(), CheckMode.INSIDE_OUT)
        : CheckOutcome.violated(id(), CheckMode.INSIDE_OUT, violations);
  }

  @Override
  public CheckOutcome checkOutsideIn(ServiceClients clients) {
    List<Violation> violations = new java.util.ArrayList<>();
    for (JsonNode saga : clients.getArray("saga", "/sagas")) {
      String state = saga.path("state").asText();
      if (state.equals("COMPLETED")
          || state.equals("ABORTED")
          || state.equals("NEEDS_INTERVENTION")) {
        continue;
      }
      if (saga.path("deadlineAt").isMissingNode() || saga.path("deadlineAt").isNull()) {
        violations.add(new Violation(id(), saga.path("sagaId").asText(), "no deadlineAt"));
      }
    }
    return violations.isEmpty()
        ? CheckOutcome.clean(id(), CheckMode.OUTSIDE_IN)
        : CheckOutcome.violated(id(), CheckMode.OUTSIDE_IN, violations);
  }
}
