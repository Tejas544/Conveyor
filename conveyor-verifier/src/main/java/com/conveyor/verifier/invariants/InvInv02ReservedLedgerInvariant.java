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
 * ARCHITECTURE.md §13 — {@code stock_items.reserved == Σ reservations.quantity WHERE status =
 * 'HELD'}, per SKU. The aggregate column must always match its own ledger.
 */
@Component
public class InvInv02ReservedLedgerInvariant implements Invariant {

  private static final String SQL =
      """
      select si.sku, si.reserved,
             coalesce((select sum(r.quantity) from reservations r
                       where r.sku = si.sku and r.status = 'HELD'), 0) as held_sum
      from stock_items si
      """;

  @Override
  public InvariantId id() {
    return InvariantId.INV_INV_02;
  }

  @Override
  public InvariantClass invariantClass() {
    return InvariantClass.SAFETY;
  }

  @Override
  public CheckOutcome checkInsideOut(ServiceDatabases db) {
    List<Map<String, Object>> rows = db.get("inventory").queryForList(SQL);
    List<Violation> violations =
        rows.stream()
            .filter(
                row ->
                    ((Number) row.get("reserved")).longValue()
                        != ((Number) row.get("held_sum")).longValue())
            .map(
                row ->
                    new Violation(
                        id(),
                        (String) row.get("sku"),
                        "reserved=%s heldSum=%s"
                            .formatted(row.get("reserved"), row.get("held_sum"))))
            .toList();
    return violations.isEmpty()
        ? CheckOutcome.clean(id(), CheckMode.INSIDE_OUT)
        : CheckOutcome.violated(id(), CheckMode.INSIDE_OUT, violations);
  }

  @Override
  public CheckOutcome checkOutsideIn(ServiceClients clients) {
    List<Violation> violations = new java.util.ArrayList<>();
    for (JsonNode item : clients.getAllPages("inventory", "/inventory")) {
      String sku = item.path("sku").asText();
      int reserved = item.path("reserved").asInt();
      int heldSum =
          clients.getArray("inventory", "/inventory/" + sku + "/reservations").stream()
              .filter(r -> "HELD".equals(r.path("status").asText()))
              .mapToInt(r -> r.path("quantity").asInt())
              .sum();
      if (reserved != heldSum) {
        violations.add(
            new Violation(id(), sku, "reserved=%d heldSum=%d".formatted(reserved, heldSum)));
      }
    }
    return violations.isEmpty()
        ? CheckOutcome.clean(id(), CheckMode.OUTSIDE_IN)
        : CheckOutcome.violated(id(), CheckMode.OUTSIDE_IN, violations);
  }
}
