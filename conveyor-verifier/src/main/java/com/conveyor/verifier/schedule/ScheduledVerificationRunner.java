package com.conveyor.verifier.schedule;

import com.conveyor.verifier.engine.RunReport;
import com.conveyor.verifier.engine.VerificationEngine;
import com.conveyor.verifier.metrics.ViolationMetrics;
import com.conveyor.verifier.report.ViolationReportWriter;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ARCHITECTURE.md §13/§15.1: the "sidecar" deployment shape — a long-lived process re-evaluating
 * the catalogue every {@code conveyor.verifier.interval} (default 10s) under {@code docker compose
 * up}. Inside-out only: outside-in is a diagnostic comparison run on demand by the one-shot mode,
 * never on this hot loop (it would mean five extra services' worth of HTTP load every 10s for a
 * number that only matters once, as a coverage argument).
 */
@Component
@Profile("!oneshot")
public class ScheduledVerificationRunner {

  private final VerificationEngine engine;
  private final ViolationMetrics metrics;
  private final ViolationReportWriter reportWriter;

  public ScheduledVerificationRunner(
      VerificationEngine engine, ViolationMetrics metrics, ViolationReportWriter reportWriter) {
    this.engine = engine;
    this.metrics = metrics;
    this.reportWriter = reportWriter;
  }

  @Scheduled(fixedDelayString = "${conveyor.verifier.interval:10000}")
  public void runCycle() {
    RunReport report = engine.runInsideOut();
    metrics.record(report);
    reportWriter.write(report);
  }
}
