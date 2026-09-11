package com.conveyor.verifier.engine;

import com.conveyor.verifier.domain.CheckMode;
import com.conveyor.verifier.domain.CheckOutcome;
import java.time.Instant;
import java.util.List;

/** One full pass over the catalogue, in one mode. */
public record RunReport(Instant timestamp, CheckMode mode, List<CheckOutcome> outcomes) {

  public boolean hasAnyViolation() {
    return outcomes.stream().anyMatch(o -> !o.violations().isEmpty());
  }

  public long violationCount() {
    return outcomes.stream().mapToLong(o -> o.violations().size()).sum();
  }
}
