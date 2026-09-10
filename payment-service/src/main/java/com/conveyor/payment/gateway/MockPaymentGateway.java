package com.conveyor.payment.gateway;

import java.util.Random;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * ARCHITECTURE.md §3.3: a deterministic-under-seed mock of a payment gateway. Every {@link
 * #charge()} call consumes exactly one {@link Random#nextDouble()} roll from the shared,
 * seed-constructed generator, so replaying the same seed through the same call sequence reproduces
 * the same sequence of outcome <em>types</em> (PLAN.md Phase 5's determinism exit criterion) —
 * {@link #resetSeed(long)} exists so a test can rewind and prove that.
 *
 * <p>{@link #armFailureMode(String, double)} is the mechanism behind {@code POST
 * /test/failure-mode} (ARCHITECTURE.md §10.4, {@code chaos} profile only): once armed, every charge
 * rolls against the armed probability first; missing the roll falls through to a normal capture,
 * never to the base failure rates below — armed and base rates are alternatives, not stacked.
 */
@Component
public class MockPaymentGateway {

  private final PaymentGatewayProperties properties;
  private Random random;
  private volatile ArmedFailureMode armed;

  public MockPaymentGateway(PaymentGatewayProperties properties) {
    this.properties = properties;
    this.random = new Random(properties.seed());
  }

  public synchronized void resetSeed(long seed) {
    this.random = new Random(seed);
  }

  public void armFailureMode(String mode, double probability) {
    this.armed = new ArmedFailureMode(mode, probability);
  }

  public void disarm() {
    this.armed = null;
  }

  public GatewayOutcome charge() {
    double roll = nextRoll();
    ArmedFailureMode current = armed;
    if (current != null) {
      return roll < current.probability() ? outcomeFor(current.mode()) : captured();
    }

    if (roll < properties.declineRate()) {
      return new GatewayOutcome.Declined();
    }
    double afterDecline = properties.declineRate();
    if (roll < afterDecline + properties.errorRate()) {
      return new GatewayOutcome.GatewayError();
    }
    double afterError = afterDecline + properties.errorRate();
    if (roll < afterError + properties.timeoutRate()) {
      return new GatewayOutcome.TimedOut();
    }
    return captured();
  }

  private synchronized double nextRoll() {
    return random.nextDouble();
  }

  private GatewayOutcome captured() {
    return new GatewayOutcome.Captured("gw_" + UUID.randomUUID());
  }

  private GatewayOutcome outcomeFor(String mode) {
    return switch (mode) {
      case "DECLINE" -> new GatewayOutcome.Declined();
      case "TIMEOUT" -> new GatewayOutcome.TimedOut();
      case "ERROR" -> new GatewayOutcome.GatewayError();
      default -> captured();
    };
  }

  private record ArmedFailureMode(String mode, double probability) {}
}
