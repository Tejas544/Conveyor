package com.conveyor.payment.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ARCHITECTURE.md §3.3: base failure rates for the mock gateway's unarmed path. Deliberately zero
 * by default — a charge only fails when a test explicitly arms {@code POST /test/failure-mode} or
 * configures these directly — so the "no double charge" and other happy-path tests don't have to
 * fight a randomly-failing gateway to be deterministic.
 */
@ConfigurationProperties(prefix = "conveyor.payment.gateway")
public record PaymentGatewayProperties(
    Long seed, Double declineRate, Double errorRate, Double timeoutRate) {

  public PaymentGatewayProperties {
    if (seed == null) {
      seed = System.nanoTime();
    }
    if (declineRate == null) {
      declineRate = 0.0;
    }
    if (errorRate == null) {
      errorRate = 0.0;
    }
    if (timeoutRate == null) {
      timeoutRate = 0.0;
    }
  }
}
