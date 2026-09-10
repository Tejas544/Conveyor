package com.conveyor.common.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ARCHITECTURE.md §9: batch 100, poll every 200ms by default. {@code enabled=false} lets a test
 * disable the scheduled poll so it can drive publishing manually (the outbox-crash-safety test).
 */
@ConfigurationProperties(prefix = "conveyor.outbox.poller")
public record OutboxPollerProperties(Boolean enabled, Integer batchSize, Long intervalMs) {

  public OutboxPollerProperties {
    if (enabled == null) {
      enabled = true;
    }
    if (batchSize == null) {
      batchSize = 100;
    }
    if (intervalMs == null) {
      intervalMs = 200L;
    }
  }
}
