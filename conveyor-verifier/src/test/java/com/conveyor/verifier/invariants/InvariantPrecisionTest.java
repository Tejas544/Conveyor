package com.conveyor.verifier.invariants;

import static com.conveyor.verifier.testsupport.SeedHelpers.insertOrder;
import static com.conveyor.verifier.testsupport.SeedHelpers.insertOrderItem;
import static com.conveyor.verifier.testsupport.SeedHelpers.insertPayment;
import static com.conveyor.verifier.testsupport.SeedHelpers.insertReservation;
import static com.conveyor.verifier.testsupport.SeedHelpers.insertSaga;
import static com.conveyor.verifier.testsupport.SeedHelpers.insertSagaStep;
import static com.conveyor.verifier.testsupport.SeedHelpers.insertShipment;
import static com.conveyor.verifier.testsupport.SeedHelpers.insertStockAdjustment;
import static com.conveyor.verifier.testsupport.SeedHelpers.insertStockItem;
import static org.assertj.core.api.Assertions.assertThat;

import com.conveyor.verifier.domain.CheckOutcome;
import com.conveyor.verifier.testsupport.MultiDatabaseTestSupport;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * ARCHITECTURE.md §13: "≥500 clean states ... produce zero violations. A checker that cries wolf is
 * worse than none." 500 independent order/saga lifecycles across the three shapes this system
 * actually produces (confirmed happy path, compensated/cancelled, and in-flight) are seeded into
 * one shared database snapshot and the full catalogue is run once against all of them together — a
 * faithful and far cheaper equivalent to literally driving 500 sagas through Kafka end-to-end,
 * which would just re-prove what Phase 6/7's own saga/dispatch integration suites already
 * established about the pipeline producing these exact shapes (see CONTEXT.md's Key Decisions Log).
 */
class InvariantPrecisionTest extends MultiDatabaseTestSupport {

  private static final List<Invariant> CATALOGUE =
      List.of(
          new InvInv01NoOversellInvariant(),
          new InvInv02ReservedLedgerInvariant(),
          new InvInv03ConservationInvariant(),
          new InvOrd01ConfirmedHasCommittedReservationInvariant(),
          new InvOrd02CancelledHasNoHeldReservationInvariant(),
          new InvOrd03CancelledNoUnrefundedCaptureInvariant(),
          new InvOrd04ConfirmedHasExactCapturedPaymentInvariant(),
          new InvPay01AtMostOneCapturedPaymentInvariant(),
          new InvSaga01NonTerminalHasDeadlineInvariant(),
          new InvSaga02CompensatedOrCompletedInvariant(),
          new InvSaga03NoStaleNonTerminalSagaInvariant(testProperties),
          new InvSaga04NoNeedsInterventionInvariant(),
          new InvSaga05OrderStatusAgreesWithSagaOutcomeInvariant(testProperties),
          new InvDsp01ConfirmedHasShipmentInvariant(),
          new InvBox01NoStaleOutboxRowInvariant(testProperties));

  @Test
  void fiveHundredCleanLifecyclesProduceZeroViolationsAcrossTheWholeCatalogue() {
    for (int i = 0; i < 300; i++) {
      seedConfirmedHappyPath(i);
    }
    for (int i = 0; i < 150; i++) {
      seedCancelledCompensated(i);
    }
    for (int i = 0; i < 50; i++) {
      seedInFlight(i);
    }

    List<CheckOutcome> outcomes =
        CATALOGUE.stream().map(inv -> inv.checkInsideOut(serviceDatabases)).toList();

    for (CheckOutcome outcome : outcomes) {
      assertThat(outcome.violations())
          .as("invariant %s should be clean across 500 seeded lifecycles", outcome.invariantId())
          .isEmpty();
    }
  }

  private void seedConfirmedHappyPath(int i) {
    String sku = "PREC-CONF-%04d".formatted(i);
    UUID orderId = UUID.randomUUID();
    UUID sagaId = UUID.randomUUID();
    int qty = 1 + (i % 3);
    BigDecimal unitPrice = new BigDecimal("5.00");
    BigDecimal total = unitPrice.multiply(BigDecimal.valueOf(qty));
    Timestamp old = Timestamp.from(Instant.now().minus(Duration.ofMinutes(2)));

    insertStockItem(serviceDatabases, sku, 10 - qty, 0);
    insertStockAdjustment(serviceDatabases, sku, 10);
    insertReservation(serviceDatabases, UUID.randomUUID(), orderId, sku, qty, "COMMITTED");

    insertOrder(serviceDatabases, orderId, "CONFIRMED", sagaId, total);
    insertOrderItem(serviceDatabases, orderId, sku, qty, unitPrice);
    insertPayment(serviceDatabases, UUID.randomUUID(), orderId, total, "CAPTURED");
    insertShipment(serviceDatabases, UUID.randomUUID(), orderId, "CREATED");

    insertSaga(serviceDatabases, sagaId, orderId, "COMPLETED", null, old, old);
    insertSagaStep(serviceDatabases, sagaId, 1, "RESERVE_INVENTORY", "FORWARD", "SUCCEEDED");
    insertSagaStep(serviceDatabases, sagaId, 2, "CHARGE_PAYMENT", "FORWARD", "SUCCEEDED");
    insertSagaStep(serviceDatabases, sagaId, 3, "CONFIRM_ORDER", "FORWARD", "SUCCEEDED");
  }

  private void seedCancelledCompensated(int i) {
    String sku = "PREC-CANC-%04d".formatted(i);
    UUID orderId = UUID.randomUUID();
    UUID sagaId = UUID.randomUUID();
    int qty = 1 + (i % 3);
    BigDecimal unitPrice = new BigDecimal("5.00");
    BigDecimal total = unitPrice.multiply(BigDecimal.valueOf(qty));
    Timestamp old = Timestamp.from(Instant.now().minus(Duration.ofMinutes(2)));

    insertStockItem(serviceDatabases, sku, 10, 0);
    insertStockAdjustment(serviceDatabases, sku, 10);
    insertReservation(serviceDatabases, UUID.randomUUID(), orderId, sku, qty, "RELEASED");

    insertOrder(serviceDatabases, orderId, "CANCELLED", sagaId, total);
    insertOrderItem(serviceDatabases, orderId, sku, qty, unitPrice);
    insertPayment(serviceDatabases, UUID.randomUUID(), orderId, total, "REFUNDED");

    insertSaga(serviceDatabases, sagaId, orderId, "ABORTED", null, old, old);
    insertSagaStep(serviceDatabases, sagaId, 1, "RESERVE_INVENTORY", "FORWARD", "SUCCEEDED");
    insertSagaStep(serviceDatabases, sagaId, 2, "CHARGE_PAYMENT", "FORWARD", "SUCCEEDED");
    insertSagaStep(serviceDatabases, sagaId, 3, "REFUND_PAYMENT", "COMPENSATION", "SUCCEEDED");
    insertSagaStep(serviceDatabases, sagaId, 4, "RELEASE_INVENTORY", "COMPENSATION", "SUCCEEDED");
  }

  private void seedInFlight(int i) {
    String sku = "PREC-INFL-%04d".formatted(i);
    UUID orderId = UUID.randomUUID();
    UUID sagaId = UUID.randomUUID();
    int qty = 1 + (i % 3);
    BigDecimal unitPrice = new BigDecimal("5.00");
    BigDecimal total = unitPrice.multiply(BigDecimal.valueOf(qty));
    Timestamp now = Timestamp.from(Instant.now());
    Timestamp future = Timestamp.from(Instant.now().plus(Duration.ofMinutes(5)));

    insertStockItem(serviceDatabases, sku, 10, qty);
    insertStockAdjustment(serviceDatabases, sku, 10);
    insertReservation(serviceDatabases, UUID.randomUUID(), orderId, sku, qty, "HELD");

    insertOrder(serviceDatabases, orderId, "INVENTORY_RESERVED", sagaId, total);
    insertOrderItem(serviceDatabases, orderId, sku, qty, unitPrice);

    insertSaga(serviceDatabases, sagaId, orderId, "CHARGING_PAYMENT", future, now, now);
    insertSagaStep(serviceDatabases, sagaId, 1, "RESERVE_INVENTORY", "FORWARD", "SUCCEEDED");
  }
}
