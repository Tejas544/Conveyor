package com.conveyor.common.chaos;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code CONVEYOR_CHAOS_CRASH_AT} / {@code CONVEYOR_CHAOS_CRASH_PROBABILITY} / {@code
 * CONVEYOR_CHAOS_DELAY_AT} (ARCHITECTURE.md §14) via Spring's relaxed environment-variable binding.
 *
 * @param crashAt the named injection point to crash at, e.g. "payment.after-commit-before-publish"
 * @param crashProbability 0.0–1.0, defaults to 1.0 (always fire when armed)
 * @param delayAt "point=millis", e.g. "inventory.before-reserve=5000"
 */
@ConfigurationProperties(prefix = "conveyor.chaos")
public record ChaosProperties(String crashAt, Double crashProbability, String delayAt) {

  public ChaosProperties {
    if (crashProbability == null) {
      crashProbability = 1.0;
    }
  }
}
