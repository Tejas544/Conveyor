package com.conveyor.verifier.metrics;

import com.conveyor.verifier.domain.CheckOutcome;
import com.conveyor.verifier.engine.RunReport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * ARCHITECTURE.md §11 — {@code conveyor_invariant_violations_total{invariant}}, the checker's own
 * output, alertable (see infra/observability/prometheus/alert-rules.yml's {@code
 * InvariantViolation} rule, written ahead of this metric in Phase 9). Only the scheduled
 * <b>inside-out</b> loop calls this — inside-out is the deployed authority (ARCHITECTURE.md §13);
 * outside-in is a diagnostic comparison, never wired to the alert.
 */
@Component
public class ViolationMetrics {

  private final MeterRegistry meterRegistry;
  private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();
  private final AtomicInteger cleanRunGauge = new AtomicInteger(1);

  public ViolationMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
    meterRegistry.gauge("conveyor_verifier_clean", cleanRunGauge);
  }

  public void record(RunReport report) {
    for (CheckOutcome outcome : report.outcomes()) {
      if (!outcome.violations().isEmpty()) {
        counterFor(outcome.invariantId().tag()).increment(outcome.violations().size());
      }
    }
    cleanRunGauge.set(report.hasAnyViolation() ? 0 : 1);
  }

  private Counter counterFor(String invariantTag) {
    return counters.computeIfAbsent(
        invariantTag,
        tag ->
            Counter.builder("conveyor_invariant_violations_total")
                .tag("invariant", tag)
                .register(meterRegistry));
  }
}
