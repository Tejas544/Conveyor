package com.conveyor.verifier.invariants;

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
 * ARCHITECTURE.md §13 — conservation: {@code on_hand == initial + Σ adjustments.delta − Σ shipped},
 * per SKU. "Initial" is made exactly zero for every SKU by construction: {@code CatalogSeedRunner}
 * (inventory-service) now writes a {@code stock_adjustments} row (reason {@code SEED}) for every
 * unit of stock a SKU is ever created with — the only place a {@code stock_items} row is ever
 * created in this codebase — so every unit this system has ever had is traceable to an audited
 * adjustment, and "shipped" is exactly {@code Σ COMMITTED reservations.quantity} (BUG-0021: the
 * only place stock ever permanently leaves {@code on_hand}). No baseline snapshot needed.
 *
 * <p><b>Not observable outside-in</b> (the default): {@code stock_adjustments} has no GET endpoint
 * anywhere in the public API — ARCHITECTURE.md §13's own illustrative argument, made concrete.
 */
@Component
public class InvInv03ConservationInvariant implements Invariant {

  private static final String SQL =
      """
      select si.sku, si.on_hand,
             coalesce((select sum(a.delta) from stock_adjustments a where a.sku = si.sku), 0) as total_in,
             coalesce((select sum(r.quantity) from reservations r
                       where r.sku = si.sku and r.status = 'COMMITTED'), 0) as total_shipped
      from stock_items si
      """;

  @Override
  public InvariantId id() {
    return InvariantId.INV_INV_03;
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
                row -> {
                  long onHand = ((Number) row.get("on_hand")).longValue();
                  long totalIn = ((Number) row.get("total_in")).longValue();
                  long shipped = ((Number) row.get("total_shipped")).longValue();
                  return onHand != totalIn - shipped;
                })
            .map(
                row ->
                    new Violation(
                        id(),
                        (String) row.get("sku"),
                        "on_hand=%s totalAdjustments=%s totalShipped=%s"
                            .formatted(
                                row.get("on_hand"), row.get("total_in"), row.get("total_shipped"))))
            .toList();
    return violations.isEmpty()
        ? CheckOutcome.clean(id(), CheckMode.INSIDE_OUT)
        : CheckOutcome.violated(id(), CheckMode.INSIDE_OUT, violations);
  }
}
