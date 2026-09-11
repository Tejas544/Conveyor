package com.conveyor.verifier.invariants;

import com.conveyor.verifier.config.ServiceDatabases;
import com.conveyor.verifier.config.VerifierProperties;
import com.conveyor.verifier.domain.CheckMode;
import com.conveyor.verifier.domain.CheckOutcome;
import com.conveyor.verifier.domain.InvariantClass;
import com.conveyor.verifier.domain.InvariantId;
import com.conveyor.verifier.domain.Violation;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * ARCHITECTURE.md §13 — no {@code outbox} row is unpublished for longer than 60s, in <b>any</b> of
 * the five service databases. The publish path ({@link com.conveyor.common.outbox.OutboxPoller}) is
 * alive.
 *
 * <p><b>Not observable outside-in</b> (the default): {@code outbox} is pure internal infrastructure
 * (ADR-7) — no service exposes it via any endpoint, and none should, since a client has no business
 * reason to see an unpublished-message queue. This is ARCHITECTURE.md §13's canonical example of
 * something outside-in structurally cannot see.
 */
@Component
public class InvBox01NoStaleOutboxRowInvariant implements Invariant {

  private static final List<String> SERVICES =
      List.of("order", "inventory", "payment", "saga", "dispatch");

  private final VerifierProperties properties;

  public InvBox01NoStaleOutboxRowInvariant(VerifierProperties properties) {
    this.properties = properties;
  }

  @Override
  public InvariantId id() {
    return InvariantId.INV_BOX_01;
  }

  @Override
  public InvariantClass invariantClass() {
    return InvariantClass.LIVENESS;
  }

  @Override
  public CheckOutcome checkInsideOut(ServiceDatabases db) {
    Timestamp cutoff = Timestamp.from(Instant.now().minus(properties.outboxLagThreshold()));
    List<Violation> violations = new ArrayList<>();
    for (String service : SERVICES) {
      List<Map<String, Object>> rows =
          db.get(service)
              .queryForList(
                  "select id, created_at from outbox where published_at is null and created_at < ?",
                  cutoff);
      for (Map<String, Object> row : rows) {
        violations.add(
            new Violation(
                id(),
                service + "-service:" + row.get("id"),
                "unpublished since %s".formatted(row.get("created_at"))));
      }
    }
    return violations.isEmpty()
        ? CheckOutcome.clean(id(), CheckMode.INSIDE_OUT)
        : CheckOutcome.violated(id(), CheckMode.INSIDE_OUT, violations);
  }
}
