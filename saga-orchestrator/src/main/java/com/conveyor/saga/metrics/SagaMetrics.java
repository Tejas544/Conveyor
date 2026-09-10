package com.conveyor.saga.metrics;

import com.conveyor.saga.domain.SagaState;
import com.conveyor.saga.repository.SagaInstanceRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/** ARCHITECTURE.md §11 — the saga-specific instruments the rigor phases consume directly. */
@Component
public class SagaMetrics {

  private static final Set<SagaState> NON_TERMINAL =
      EnumSet.of(
          SagaState.STARTED,
          SagaState.RESERVING_INVENTORY,
          SagaState.CHARGING_PAYMENT,
          SagaState.CONFIRMING,
          SagaState.ABORTING,
          SagaState.COMPENSATING_INVENTORY,
          SagaState.COMPENSATING_PAYMENT);

  private final MeterRegistry registry;

  public SagaMetrics(MeterRegistry registry, SagaInstanceRepository sagaInstanceRepository) {
    this.registry = registry;
    registry.gauge(
        "conveyor_saga_active",
        sagaInstanceRepository,
        repo -> NON_TERMINAL.stream().mapToLong(state -> repo.findByState(state).size()).sum());
    // Not in ARCHITECTURE.md §11's original table, added here to back the "NEEDS_INTERVENTION >
    // 0" alert rule PLAN.md's Phase 9 deliverable list names, and to give INV-SAGA-04
    // (count(NEEDS_INTERVENTION) == 0) a live signal ahead of Phase 10's own DB-level check.
    registry.gauge(
        "conveyor_saga_needs_intervention",
        sagaInstanceRepository,
        repo -> repo.findByState(SagaState.NEEDS_INTERVENTION).size());
  }

  public void recordStepDuration(String step, String direction, Instant startedAt) {
    // publishPercentileHistogram(): a plain Timer only exposes _count/_sum/_max to Prometheus —
    // histogram_quantile() (the Pipeline Latency and Saga Health dashboards, ARCHITECTURE.md §11's
    // own p50/p95/p99 requirement) needs the _bucket series this opts into. Found live in Phase 9
    // ("Grafana dashboards populate against a local load run" — they didn't, for these two).
    Timer.builder("conveyor_saga_step_duration_seconds")
        .tag("step", step)
        .tag("direction", direction)
        .publishPercentileHistogram()
        .register(registry)
        .record(Duration.between(startedAt, Instant.now()));
  }

  public void recordSagaTerminal(String outcome, Instant createdAt) {
    Counter.builder("conveyor_saga_terminal_total")
        .tag("outcome", outcome)
        .register(registry)
        .increment();
    Timer.builder("conveyor_saga_duration_seconds")
        .tag("outcome", outcome)
        .publishPercentileHistogram()
        .register(registry)
        .record(Duration.between(createdAt, Instant.now()));
  }

  public void recordTimeout(String step) {
    Counter.builder("conveyor_saga_timeouts_total")
        .tag("step", step)
        .register(registry)
        .increment();
  }

  public void recordInboxDuplicate() {
    Counter.builder("conveyor_inbox_duplicates_total")
        .tag("consumer", "saga-orchestrator")
        .register(registry)
        .increment();
  }
}
