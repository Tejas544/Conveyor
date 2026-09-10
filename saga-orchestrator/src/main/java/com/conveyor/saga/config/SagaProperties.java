package com.conveyor.saga.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ARCHITECTURE.md §7.4 — timeouts and the deadline-sweep policy, bound from {@code
 * application.yml}.
 */
@ConfigurationProperties(prefix = "conveyor.saga")
public record SagaProperties(
    Duration forwardStepTimeout,
    Duration compensationStepTimeout,
    Duration sweepInterval,
    int maxCompensationAttempts) {}
