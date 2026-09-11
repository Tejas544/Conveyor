package com.conveyor.verifier.cli;

import com.conveyor.verifier.domain.CheckOutcome;
import com.conveyor.verifier.domain.InvariantId;
import com.conveyor.verifier.engine.RunReport;
import com.conveyor.verifier.engine.VerificationEngine;
import com.conveyor.verifier.metrics.ViolationMetrics;
import com.conveyor.verifier.report.ViolationReportWriter;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * PLAN.md Phase 10: {@code make invariant-check} — a single pass that exits non-zero on a
 * violation, so CI (and a human) can gate on it. Also the K8s {@code CronJob} shape
 * (infra/k8s/conveyor-verifier-cronjob.yaml): a CronJob runs a container to completion on a
 * schedule, which is exactly this mode, not the continuous sidecar loop.
 *
 * <p>Runs both checking modes and prints the inside-out/outside-in coverage comparison
 * (ARCHITECTURE.md §13's "measured" requirement) — but only <b>inside-out</b> violations affect the
 * exit code. Outside-in is diagnostic: a "not observable" or an outside-in miss is not itself a
 * correctness bug, and gating on it would make the checker cry wolf about its own known, documented
 * blind spots (docs/INVARIANTS.md).
 */
@Component
@Profile("oneshot")
public class OneShotRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(OneShotRunner.class);

  private final VerificationEngine engine;
  private final ViolationMetrics metrics;
  private final ViolationReportWriter reportWriter;
  private final ConfigurableApplicationContext context;

  public OneShotRunner(
      VerificationEngine engine,
      ViolationMetrics metrics,
      ViolationReportWriter reportWriter,
      ConfigurableApplicationContext context) {
    this.engine = engine;
    this.metrics = metrics;
    this.reportWriter = reportWriter;
    this.context = context;
  }

  @Override
  public void run(ApplicationArguments args) {
    RunReport insideOut = engine.runInsideOut();
    metrics.record(insideOut);
    reportWriter.write(insideOut);

    RunReport outsideIn = engine.runOutsideIn();
    logCoverageComparison(insideOut, outsideIn);

    int exitCode = insideOut.hasAnyViolation() ? 1 : 0;
    log.info(
        "conveyor-verifier one-shot run complete: {} invariants, {} inside-out violation(s) — exit {}",
        engine.catalogueSize(),
        insideOut.violationCount(),
        exitCode);
    System.exit(SpringApplication.exit(context, () -> exitCode));
  }

  private void logCoverageComparison(RunReport insideOut, RunReport outsideIn) {
    Map<InvariantId, CheckOutcome> outsideById = new HashMap<>();
    for (CheckOutcome outcome : outsideIn.outcomes()) {
      outsideById.put(outcome.invariantId(), outcome);
    }
    for (CheckOutcome inside : insideOut.outcomes()) {
      CheckOutcome outside = outsideById.get(inside.invariantId());
      String outsideSummary =
          outside == null
              ? "n/a"
              : (!outside.observable()
                  ? "NOT_OBSERVABLE (%s)".formatted(outside.notObservableReason())
                  : (outside.isClean() ? "clean" : outside.violations().size() + " violation(s)"));
      log.info(
          "coverage {}: insideOut={} outsideIn={}",
          inside.invariantId().tag(),
          inside.isClean() ? "clean" : inside.violations().size() + " violation(s)",
          outsideSummary);
    }
  }
}
