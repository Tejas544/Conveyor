package com.conveyor.verifier.invariants;

import static com.conveyor.verifier.testsupport.SeedHelpers.dropConstraints;
import static com.conveyor.verifier.testsupport.SeedHelpers.insertOrder;
import static com.conveyor.verifier.testsupport.SeedHelpers.insertOrderItem;
import static com.conveyor.verifier.testsupport.SeedHelpers.insertOutboxRow;
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
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * ARCHITECTURE.md §13 / PLAN.md Phase 10's negative-control discipline: for every one of the 15
 * invariants, a deliberately corrupted state is constructed and the checker must flag it, with the
 * correct offending ID. Two ({@code INV-INV-01}, {@code INV-PAY-01}) are protected by a real
 * Postgres constraint and can only be violated by dropping that constraint first — recorded inline,
 * not silently worked around, per ARCHITECTURE.md §13's "named as such" requirement (both ARE
 * constructible, just not via any application-level write).
 */
class InvariantSoundnessTest extends MultiDatabaseTestSupport {

  @Test
  void invInv01FlagsOversellOnceTheCheckConstraintIsBypassed() {
    String sku = "SOUND-INV01-" + UUID.randomUUID();
    insertStockItem(serviceDatabases, sku, 5, 5);
    dropConstraints(serviceDatabases, "inventory", "stock_items", 'c');
    serviceDatabases
        .get("inventory")
        .update("update stock_items set reserved = 10 where sku = ?", sku);

    CheckOutcome outcome = new InvInv01NoOversellInvariant().checkInsideOut(serviceDatabases);
    assertThat(outcome.violations()).anyMatch(v -> v.offendingId().equals(sku));
  }

  @Test
  void invInv02FlagsALedgerMismatch() {
    String sku = "SOUND-INV02-" + UUID.randomUUID();
    insertStockItem(serviceDatabases, sku, 10, 4);
    insertReservation(serviceDatabases, UUID.randomUUID(), UUID.randomUUID(), sku, 4, "HELD");
    // reserved column says 4, but a second HELD reservation the column never accounted for exists.
    insertReservation(serviceDatabases, UUID.randomUUID(), UUID.randomUUID(), sku, 3, "HELD");

    CheckOutcome outcome = new InvInv02ReservedLedgerInvariant().checkInsideOut(serviceDatabases);
    assertThat(outcome.violations()).anyMatch(v -> v.offendingId().equals(sku));
  }

  @Test
  void invInv03FlagsConservationBreak() {
    String sku = "SOUND-INV03-" + UUID.randomUUID();
    insertStockItem(serviceDatabases, sku, 20, 0);
    insertStockAdjustment(serviceDatabases, sku, 15); // says only 15 units were ever introduced

    CheckOutcome outcome = new InvInv03ConservationInvariant().checkInsideOut(serviceDatabases);
    assertThat(outcome.violations()).anyMatch(v -> v.offendingId().equals(sku));
  }

  @Test
  void invOrd01FlagsAConfirmedOrderWithNoCommittedReservation() {
    UUID orderId = UUID.randomUUID();
    String sku = "SOUND-ORD01-" + UUID.randomUUID();
    insertOrder(serviceDatabases, orderId, "CONFIRMED", null, new BigDecimal("10.00"));
    insertOrderItem(serviceDatabases, orderId, sku, 2, new BigDecimal("5.00"));
    // No reservation at all — the gap BUG-0021 closed, reproduced deliberately here.

    CheckOutcome outcome =
        new InvOrd01ConfirmedHasCommittedReservationInvariant().checkInsideOut(serviceDatabases);
    assertThat(outcome.violations()).anyMatch(v -> v.offendingId().equals(orderId.toString()));
  }

  @Test
  void invOrd02FlagsACancelledOrderWithAStillHeldReservation() {
    UUID orderId = UUID.randomUUID();
    String sku = "SOUND-ORD02-" + UUID.randomUUID();
    insertOrder(serviceDatabases, orderId, "CANCELLED", null, new BigDecimal("10.00"));
    insertStockItem(serviceDatabases, sku, 10, 2);
    insertReservation(serviceDatabases, UUID.randomUUID(), orderId, sku, 2, "HELD");

    CheckOutcome outcome =
        new InvOrd02CancelledHasNoHeldReservationInvariant().checkInsideOut(serviceDatabases);
    assertThat(outcome.violations()).anyMatch(v -> v.offendingId().equals(orderId.toString()));
  }

  @Test
  void invOrd03FlagsACancelledOrderWithAnUnrefundedCapture() {
    UUID orderId = UUID.randomUUID();
    insertOrder(serviceDatabases, orderId, "CANCELLED", null, new BigDecimal("42.00"));
    insertPayment(
        serviceDatabases, UUID.randomUUID(), orderId, new BigDecimal("42.00"), "CAPTURED");

    CheckOutcome outcome =
        new InvOrd03CancelledNoUnrefundedCaptureInvariant().checkInsideOut(serviceDatabases);
    assertThat(outcome.violations()).anyMatch(v -> v.offendingId().equals(orderId.toString()));
  }

  @Test
  void invOrd04FlagsAConfirmedOrderWithAPaymentAmountMismatch() {
    UUID orderId = UUID.randomUUID();
    insertOrder(serviceDatabases, orderId, "CONFIRMED", null, new BigDecimal("100.00"));
    insertPayment(
        serviceDatabases, UUID.randomUUID(), orderId, new BigDecimal("50.00"), "CAPTURED");

    CheckOutcome outcome =
        new InvOrd04ConfirmedHasExactCapturedPaymentInvariant().checkInsideOut(serviceDatabases);
    assertThat(outcome.violations()).anyMatch(v -> v.offendingId().equals(orderId.toString()));
  }

  @Test
  void invPay01FlagsADoubleCaptureOnceTheUniqueConstraintIsBypassed() {
    UUID orderId = UUID.randomUUID();
    dropConstraints(serviceDatabases, "payment", "payments", 'u');
    insertPayment(
        serviceDatabases, UUID.randomUUID(), orderId, new BigDecimal("10.00"), "CAPTURED");
    insertPayment(
        serviceDatabases, UUID.randomUUID(), orderId, new BigDecimal("10.00"), "CAPTURED");

    CheckOutcome outcome =
        new InvPay01AtMostOneCapturedPaymentInvariant().checkInsideOut(serviceDatabases);
    assertThat(outcome.violations()).anyMatch(v -> v.offendingId().equals(orderId.toString()));
  }

  @Test
  void invSaga01FlagsANonTerminalSagaWithNoDeadline() {
    UUID sagaId = UUID.randomUUID();
    insertSaga(
        serviceDatabases,
        sagaId,
        UUID.randomUUID(),
        "RESERVING_INVENTORY",
        null,
        Timestamp.from(Instant.now()),
        Timestamp.from(Instant.now()));

    CheckOutcome outcome =
        new InvSaga01NonTerminalHasDeadlineInvariant().checkInsideOut(serviceDatabases);
    assertThat(outcome.violations()).anyMatch(v -> v.offendingId().equals(sagaId.toString()));
  }

  @Test
  void invSaga02FlagsAnUncompensatedForwardStepOnAnAbortedSaga() {
    UUID sagaId = UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.now());
    insertSaga(serviceDatabases, sagaId, UUID.randomUUID(), "ABORTED", null, now, now);
    insertSagaStep(serviceDatabases, sagaId, 1, "RESERVE_INVENTORY", "FORWARD", "SUCCEEDED");
    // No RELEASE_INVENTORY compensation step recorded — the saga guarantee broken.

    CheckOutcome outcome =
        new InvSaga02CompensatedOrCompletedInvariant().checkInsideOut(serviceDatabases);
    assertThat(outcome.violations()).anyMatch(v -> v.offendingId().equals(sagaId.toString()));
  }

  @Test
  void invSaga03FlagsANonTerminalSagaOlderThanMaxAge() {
    UUID sagaId = UUID.randomUUID();
    Timestamp ancient = Timestamp.from(Instant.now().minus(java.time.Duration.ofHours(1)));
    insertSaga(
        serviceDatabases, sagaId, UUID.randomUUID(), "CHARGING_PAYMENT", ancient, ancient, ancient);

    CheckOutcome outcome =
        new InvSaga03NoStaleNonTerminalSagaInvariant(testProperties)
            .checkInsideOut(serviceDatabases);
    assertThat(outcome.violations()).anyMatch(v -> v.offendingId().equals(sagaId.toString()));
  }

  @Test
  void invSaga04FlagsANeedsInterventionSaga() {
    UUID sagaId = UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.now());
    insertSaga(serviceDatabases, sagaId, UUID.randomUUID(), "NEEDS_INTERVENTION", null, now, now);

    CheckOutcome outcome =
        new InvSaga04NoNeedsInterventionInvariant().checkInsideOut(serviceDatabases);
    assertThat(outcome.violations()).anyMatch(v -> v.offendingId().equals(sagaId.toString()));
  }

  @Test
  void invSaga05FlagsAConfirmedSagaWhoseOrderNeverProjectedToConfirmed() {
    UUID orderId = UUID.randomUUID();
    UUID sagaId = UUID.randomUUID();
    Timestamp old = Timestamp.from(Instant.now().minus(java.time.Duration.ofMinutes(5)));
    insertOrder(serviceDatabases, orderId, "PAYMENT_CHARGED", sagaId, new BigDecimal("10.00"));
    insertSaga(serviceDatabases, sagaId, orderId, "COMPLETED", null, old, old);

    CheckOutcome outcome =
        new InvSaga05OrderStatusAgreesWithSagaOutcomeInvariant(testProperties)
            .checkInsideOut(serviceDatabases);
    assertThat(outcome.violations()).anyMatch(v -> v.offendingId().equals(orderId.toString()));
  }

  @Test
  void invDsp01FlagsAConfirmedOrderWithNoShipment() {
    UUID orderId = UUID.randomUUID();
    insertOrder(serviceDatabases, orderId, "CONFIRMED", null, new BigDecimal("10.00"));

    CheckOutcome outcome =
        new InvDsp01ConfirmedHasShipmentInvariant().checkInsideOut(serviceDatabases);
    assertThat(outcome.violations()).anyMatch(v -> v.offendingId().equals(orderId.toString()));
  }

  @Test
  void invDsp01FlagsACancelledOrderThatStillHasAShipment() {
    UUID orderId = UUID.randomUUID();
    insertOrder(serviceDatabases, orderId, "CANCELLED", null, new BigDecimal("10.00"));
    insertShipment(serviceDatabases, UUID.randomUUID(), orderId, "CREATED");

    CheckOutcome outcome =
        new InvDsp01ConfirmedHasShipmentInvariant().checkInsideOut(serviceDatabases);
    assertThat(outcome.violations()).anyMatch(v -> v.offendingId().equals(orderId.toString()));
  }

  @Test
  void invBox01FlagsAStaleUnpublishedOutboxRowInEveryService() {
    Timestamp ancient = Timestamp.from(Instant.now().minus(java.time.Duration.ofMinutes(5)));
    UUID id = UUID.randomUUID();
    insertOutboxRow(serviceDatabases, "order", id, ancient, null);

    CheckOutcome outcome =
        new InvBox01NoStaleOutboxRowInvariant(testProperties).checkInsideOut(serviceDatabases);
    assertThat(outcome.violations()).anyMatch(v -> v.offendingId().equals("order-service:" + id));
  }
}
