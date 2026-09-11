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
import org.springframework.stereotype.Component;

/**
 * ARCHITECTURE.md §13 — the saga guarantee itself, stated directly: for every terminal saga, each
 * {@code FORWARD} step that {@code SUCCEEDED} either belongs to a {@code COMPLETED} saga, or has a
 * matching {@code COMPENSATION} step that also {@code SUCCEEDED}. The forward→compensation mapping
 * mirrors saga-orchestrator's own {@code SagaSteps.compensationFor} (this module has no compile
 * dependency on saga-orchestrator, so it is restated here, not imported — see docs/INVARIANTS.md).
 */
@Component
public class InvSaga02CompensatedOrCompletedInvariant implements Invariant {

  private static final Map<String, String> COMPENSATION_FOR =
      Map.of(
          "RESERVE_INVENTORY", "RELEASE_INVENTORY",
          "CHARGE_PAYMENT", "REFUND_PAYMENT");

  @Override
  public InvariantId id() {
    return InvariantId.INV_SAGA_02;
  }

  @Override
  public InvariantClass invariantClass() {
    return InvariantClass.SAFETY;
  }

  @Override
  public CheckOutcome checkInsideOut(ServiceDatabases db) {
    List<Map<String, Object>> sagas =
        db.get("saga")
            .queryForList(
                "select id, state from saga_instances where state in "
                    + "('COMPLETED','ABORTED','NEEDS_INTERVENTION')");
    List<Violation> violations = new ArrayList<>();
    for (Map<String, Object> saga : sagas) {
      String sagaId = saga.get("id").toString();
      String state = (String) saga.get("state");
      List<Map<String, Object>> steps =
          db.get("saga")
              .queryForList(
                  "select step, direction, status from saga_steps where saga_id = ?::uuid", sagaId);
      for (Map<String, Object> step : steps) {
        if (!"FORWARD".equals(step.get("direction")) || !"SUCCEEDED".equals(step.get("status"))) {
          continue;
        }
        String forwardStep = (String) step.get("step");
        if (isCompensatedOrCompleted(forwardStep, state, steps)) {
          continue;
        }
        violations.add(
            new Violation(
                id(),
                sagaId,
                "%s SUCCEEDED with no compensation, saga state=%s".formatted(forwardStep, state)));
      }
    }
    return violations.isEmpty()
        ? CheckOutcome.clean(id(), CheckMode.INSIDE_OUT)
        : CheckOutcome.violated(id(), CheckMode.INSIDE_OUT, violations);
  }

  private boolean isCompensatedOrCompleted(
      String forwardStep, String sagaState, List<Map<String, Object>> steps) {
    if ("COMPLETED".equals(sagaState)) {
      return true;
    }
    String compensation = COMPENSATION_FOR.get(forwardStep);
    if (compensation == null) {
      // CONFIRM_ORDER has no compensation — only valid if the saga actually completed.
      return false;
    }
    return steps.stream()
        .anyMatch(s -> compensation.equals(s.get("step")) && "SUCCEEDED".equals(s.get("status")));
  }

  @Override
  public CheckOutcome checkOutsideIn(ServiceClients clients) {
    List<JsonNode> terminalSagas =
        clients.getArray("saga", "/sagas").stream()
            .filter(
                s -> {
                  String state = s.path("state").asText();
                  return state.equals("COMPLETED")
                      || state.equals("ABORTED")
                      || state.equals("NEEDS_INTERVENTION");
                })
            .toList();
    List<Violation> violations = new ArrayList<>();
    for (JsonNode summary : terminalSagas) {
      String orderId = summary.path("orderId").asText();
      JsonNode detail = clients.getOrNull("saga", "/sagas/" + orderId);
      if (detail == null) {
        continue;
      }
      String state = detail.path("state").asText();
      List<JsonNode> steps = new ArrayList<>();
      detail.path("steps").forEach(steps::add);
      for (JsonNode step : steps) {
        if (!"FORWARD".equals(step.path("direction").asText())
            || !"SUCCEEDED".equals(step.path("status").asText())) {
          continue;
        }
        String forwardStep = step.path("step").asText();
        boolean ok;
        if ("COMPLETED".equals(state)) {
          ok = true;
        } else {
          String compensation = COMPENSATION_FOR.get(forwardStep);
          ok =
              compensation != null
                  && steps.stream()
                      .anyMatch(
                          s ->
                              compensation.equals(s.path("step").asText())
                                  && "SUCCEEDED".equals(s.path("status").asText()));
        }
        if (!ok) {
          violations.add(
              new Violation(
                  id(),
                  detail.path("sagaId").asText(),
                  "%s SUCCEEDED with no compensation, saga state=%s"
                      .formatted(forwardStep, state)));
        }
      }
    }
    return violations.isEmpty()
        ? CheckOutcome.clean(id(), CheckMode.OUTSIDE_IN)
        : CheckOutcome.violated(id(), CheckMode.OUTSIDE_IN, violations);
  }
}
