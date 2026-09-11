package com.conveyor.verifier.report;

import com.conveyor.verifier.config.VerifierProperties;
import com.conveyor.verifier.domain.CheckOutcome;
import com.conveyor.verifier.engine.RunReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * PLAN.md Phase 10: "writes a violation report (invariant, offending IDs, timestamp) to a file and
 * to stdout as structured JSON." Every cycle overwrites {@code latest-report.json} (the current
 * state, for a human or a readiness probe to inspect); a cycle with at least one violation also
 * appends one line to {@code violations.jsonl} (the append-only history CLAUDE.md §1 asks
 * `RESULTS.md`-style artifacts to preserve, kept here instead since it is generated, not authored).
 */
@Component
public class ViolationReportWriter {

  private static final Logger log = LoggerFactory.getLogger(ViolationReportWriter.class);

  private final ObjectMapper objectMapper;
  private final Path directory;

  public ViolationReportWriter(ObjectMapper objectMapper, VerifierProperties properties) {
    this.objectMapper = objectMapper;
    this.directory = Path.of(properties.reportDirectory());
  }

  public void write(RunReport report) {
    Map<String, Object> document = toDocument(report);
    String json = writeAsJson(document);

    if (report.hasAnyViolation()) {
      log.warn("conveyor-verifier violation report: {}", json);
    } else {
      log.info(
          "conveyor-verifier clean run: {} invariants checked, 0 violations",
          report.outcomes().size());
    }

    try {
      Files.createDirectories(directory);
      Files.writeString(
          directory.resolve("latest-report.json"),
          json + System.lineSeparator(),
          StandardCharsets.UTF_8);
      if (report.hasAnyViolation()) {
        Files.writeString(
            directory.resolve("violations.jsonl"),
            json + System.lineSeparator(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
      }
    } catch (IOException e) {
      log.error("Failed to write violation report to {}", directory, e);
    }
  }

  private Map<String, Object> toDocument(RunReport report) {
    List<Map<String, Object>> outcomes = report.outcomes().stream().map(this::toDocument).toList();
    return Map.of(
        "timestamp", report.timestamp().toString(),
        "mode", report.mode().name(),
        "invariantsChecked", report.outcomes().size(),
        "violationCount", report.violationCount(),
        "outcomes", outcomes);
  }

  private Map<String, Object> toDocument(CheckOutcome outcome) {
    return Map.of(
        "invariant",
        outcome.invariantId().tag(),
        "observable",
        outcome.observable(),
        "notObservableReason",
        outcome.notObservableReason() == null ? "" : outcome.notObservableReason(),
        "violations",
        outcome.violations().stream()
            .map(v -> Map.of("offendingId", v.offendingId(), "detail", v.detail()))
            .toList());
  }

  private String writeAsJson(Map<String, Object> document) {
    try {
      return objectMapper.writeValueAsString(document);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to serialize violation report", e);
    }
  }
}
