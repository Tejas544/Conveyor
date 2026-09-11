package com.conveyor.verifier.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** ARCHITECTURE.md §12/§13, PLAN.md Phase 10 — every knob the checker needs, one place. */
@ConfigurationProperties(prefix = "conveyor.verifier")
public record VerifierProperties(
    Postgres postgres,
    Http http,
    Duration interval,
    Duration maxSagaAge,
    Duration outboxLagThreshold,
    Duration terminalGrace,
    String reportDirectory) {

  /**
   * One Postgres instance (ARCHITECTURE.md §4), one shared read-only role
   * (VERIFIER_DB_USER/PASSWORD), five database names — never a service's own credentials.
   */
  public record Postgres(
      String host,
      int port,
      String username,
      String password,
      String orderDb,
      String inventoryDb,
      String paymentDb,
      String sagaDb,
      String dispatchDb) {}

  /**
   * Base URLs for the five services' already-public REST APIs — outside-in mode reads only these.
   */
  public record Http(
      String orderBaseUrl,
      String inventoryBaseUrl,
      String paymentBaseUrl,
      String sagaBaseUrl,
      String dispatchBaseUrl) {}
}
