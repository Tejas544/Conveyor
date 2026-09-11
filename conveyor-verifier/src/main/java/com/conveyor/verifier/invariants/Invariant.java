package com.conveyor.verifier.invariants;

import com.conveyor.verifier.config.ServiceClients;
import com.conveyor.verifier.config.ServiceDatabases;
import com.conveyor.verifier.domain.CheckMode;
import com.conveyor.verifier.domain.CheckOutcome;
import com.conveyor.verifier.domain.InvariantClass;
import com.conveyor.verifier.domain.InvariantId;

/**
 * One row of ARCHITECTURE.md §13's catalogue. Every invariant implements the inside-out check for
 * real; {@link #checkOutsideIn} defaults to "not observable" and is overridden only where the
 * existing public REST surface genuinely lets a client-only checker see it — see docs/INVARIANTS.md
 * for the reasoning behind each yes/no.
 */
public interface Invariant {

  InvariantId id();

  InvariantClass invariantClass();

  CheckOutcome checkInsideOut(ServiceDatabases db);

  default CheckOutcome checkOutsideIn(ServiceClients clients) {
    return CheckOutcome.notObservable(id(), CheckMode.OUTSIDE_IN, defaultNotObservableReason());
  }

  default String defaultNotObservableReason() {
    return "no public REST endpoint exposes the data this invariant depends on";
  }
}
