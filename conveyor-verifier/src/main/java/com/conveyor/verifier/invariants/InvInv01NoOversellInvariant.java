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
 * ARCHITECTURE.md §13 — {@code on_hand - reserved >= 0} for every SKU. ADR-9's table {@code CHECK}
 * constraint makes this structurally impossible to violate via any application write; the
 * seeded-violation harness for this one has to drop and re-add that constraint to construct a
 * violating row at all (see {@code InvariantSoundnessTest}).
 */
@Component
public class InvInv01NoOversellInvariant implements Invariant {

  @Override
  public InvariantId id() {
    return InvariantId.INV_INV_01;
  }

  @Override
  public InvariantClass invariantClass() {
    return InvariantClass.SAFETY;
  }

  @Override
  public CheckOutcome checkInsideOut(ServiceDatabases db) {
    List<Map<String, Object>> rows =
        db.get("inventory")
            .queryForList(
                "select sku, on_hand, reserved from stock_items where on_hand - reserved < 0");
    List<Violation> violations =
        rows.stream()
            .map(
                row ->
                    new Violation(
                        id(),
                        (String) row.get("sku"),
                        "on_hand=%s reserved=%s"
                            .formatted(row.get("on_hand"), row.get("reserved"))))
            .toList();
    return violations.isEmpty()
        ? CheckOutcome.clean(id(), CheckMode.INSIDE_OUT)
        : CheckOutcome.violated(id(), CheckMode.INSIDE_OUT, violations);
  }

  @Override
  public CheckOutcome checkOutsideIn(ServiceClients clients) {
    List<Violation> violations =
        clients.getAllPages("inventory", "/inventory").stream()
            .filter(node -> node.path("onHand").asInt() - node.path("reserved").asInt() < 0)
            .map(
                node ->
                    new Violation(
                        id(),
                        node.path("sku").asText(),
                        "onHand=%s reserved=%s"
                            .formatted(node.path("onHand").asInt(), node.path("reserved").asInt())))
            .toList();
    return violations.isEmpty()
        ? CheckOutcome.clean(id(), CheckMode.OUTSIDE_IN)
        : CheckOutcome.violated(id(), CheckMode.OUTSIDE_IN, violations);
  }
}
