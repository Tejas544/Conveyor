package com.conveyor.verifier.domain;

import java.util.List;

/**
 * The result of evaluating one invariant in one mode. {@code observable=false} means this mode
 * structurally cannot evaluate this invariant at all (e.g. outside-in and the outbox table) — that
 * is a fact about the checking mode, not a clean pass, and is reported distinctly so it is never
 * miscounted as "checked and found no violations" (ARCHITECTURE.md §13's negative-control
 * discipline: an invariant that can't be checked is named as such, not counted as a pass).
 */
public record CheckOutcome(
    InvariantId invariantId,
    CheckMode mode,
    boolean observable,
    String notObservableReason,
    List<Violation> violations) {

  public static CheckOutcome clean(InvariantId id, CheckMode mode) {
    return new CheckOutcome(id, mode, true, null, List.of());
  }

  public static CheckOutcome violated(InvariantId id, CheckMode mode, List<Violation> violations) {
    return new CheckOutcome(id, mode, true, null, violations);
  }

  public static CheckOutcome notObservable(InvariantId id, CheckMode mode, String reason) {
    return new CheckOutcome(id, mode, false, reason, List.of());
  }

  public boolean isClean() {
    return observable && violations.isEmpty();
  }
}
