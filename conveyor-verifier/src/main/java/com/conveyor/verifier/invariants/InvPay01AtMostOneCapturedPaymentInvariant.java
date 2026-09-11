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
 * ARCHITECTURE.md §13 — at most one {@code CAPTURED} payment per {@code order_id}. No double
 * charge. {@code payments.order_id unique} already makes this structurally impossible via any
 * application write — like INV-INV-01, the seeded-violation harness has to drop that constraint to
 * construct a violating state at all.
 *
 * <p><b>Not observable outside-in</b> (the default): {@code GET /payments/{orderId}} returns a
 * single object by its own contract — if the database somehow held two rows for one order, the
 * endpoint's `findByOrderId` (an {@code Optional}-returning query) would only ever surface one of
 * them, silently. The API's own shape assumes the invariant already holds; it cannot be used to
 * check it.
 */
@Component
public class InvPay01AtMostOneCapturedPaymentInvariant implements Invariant {

  @Override
  public InvariantId id() {
    return InvariantId.INV_PAY_01;
  }

  @Override
  public InvariantClass invariantClass() {
    return InvariantClass.SAFETY;
  }

  @Override
  public CheckOutcome checkInsideOut(ServiceDatabases db) {
    List<Map<String, Object>> rows =
        db.get("payment")
            .queryForList(
                "select order_id, count(*) as n from payments where status = 'CAPTURED' "
                    + "group by order_id having count(*) > 1");
    List<Violation> violations =
        rows.stream()
            .map(
                row ->
                    new Violation(
                        id(),
                        row.get("order_id").toString(),
                        "%s CAPTURED payments for one order".formatted(row.get("n"))))
            .toList();
    return violations.isEmpty()
        ? CheckOutcome.clean(id(), CheckMode.INSIDE_OUT)
        : CheckOutcome.violated(id(), CheckMode.INSIDE_OUT, violations);
  }
}
