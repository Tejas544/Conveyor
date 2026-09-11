package com.conveyor.verifier.invariants;

import com.conveyor.verifier.config.ServiceClients;
import com.conveyor.verifier.config.ServiceDatabases;
import com.conveyor.verifier.domain.CheckMode;
import com.conveyor.verifier.domain.CheckOutcome;
import com.conveyor.verifier.domain.InvariantClass;
import com.conveyor.verifier.domain.InvariantId;
import com.conveyor.verifier.domain.Violation;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * ARCHITECTURE.md §13 — {@code count(NEEDS_INTERVENTION) == 0}. Non-zero is not a checker failure —
 * it is a real finding, reported with the saga IDs (the operator hasn't run {@code POST
 * /sagas/{id}/retry} yet).
 */
@Component
public class InvSaga04NoNeedsInterventionInvariant implements Invariant {

  @Override
  public InvariantId id() {
    return InvariantId.INV_SAGA_04;
  }

  @Override
  public InvariantClass invariantClass() {
    return InvariantClass.LIVENESS;
  }

  @Override
  public CheckOutcome checkInsideOut(ServiceDatabases db) {
    List<Map<String, Object>> rows =
        db.get("saga")
            .queryForList("select id from saga_instances where state = 'NEEDS_INTERVENTION'");
    List<Violation> violations =
        rows.stream()
            .map(
                row -> new Violation(id(), row.get("id").toString(), "stuck in NEEDS_INTERVENTION"))
            .toList();
    return violations.isEmpty()
        ? CheckOutcome.clean(id(), CheckMode.INSIDE_OUT)
        : CheckOutcome.violated(id(), CheckMode.INSIDE_OUT, violations);
  }

  @Override
  public CheckOutcome checkOutsideIn(ServiceClients clients) {
    List<com.conveyor.verifier.domain.Violation> violations =
        clients.getArray("saga", "/sagas?state=NEEDS_INTERVENTION").stream()
            .map(s -> new Violation(id(), s.path("sagaId").asText(), "stuck in NEEDS_INTERVENTION"))
            .toList();
    return violations.isEmpty()
        ? CheckOutcome.clean(id(), CheckMode.OUTSIDE_IN)
        : CheckOutcome.violated(id(), CheckMode.OUTSIDE_IN, violations);
  }
}
