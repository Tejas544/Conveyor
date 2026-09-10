package com.conveyor.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * PLAN.md Phase 5: the same seed produces the same gateway outcome sequence — a plain unit test, no
 * Spring context needed, since {@link MockPaymentGateway} takes its dependencies as plain
 * constructor arguments.
 */
class MockPaymentGatewayDeterminismTest {

  @Test
  void sameSeedProducesTheSameOutcomeTypeSequence() {
    PaymentGatewayProperties properties = new PaymentGatewayProperties(null, 0.3, 0.2, 0.1);
    MockPaymentGateway gateway = new MockPaymentGateway(properties);

    gateway.resetSeed(42L);
    List<Class<?>> firstRun = chargeNTimes(gateway, 50);

    gateway.resetSeed(42L);
    List<Class<?>> secondRun = chargeNTimes(gateway, 50);

    assertThat(secondRun).isEqualTo(firstRun);
    // Sanity: with declineRate 0.3 + errorRate 0.2 + timeoutRate 0.1 over 50 rolls, the sequence
    // should not be uniformly one outcome — otherwise this test would pass trivially.
    assertThat(firstRun.stream().distinct().count()).isGreaterThan(1);
  }

  @Test
  void differentSeedsCanProduceADifferentSequence() {
    PaymentGatewayProperties properties = new PaymentGatewayProperties(null, 0.3, 0.2, 0.1);
    MockPaymentGateway gateway = new MockPaymentGateway(properties);

    gateway.resetSeed(1L);
    List<Class<?>> runOne = chargeNTimes(gateway, 50);

    gateway.resetSeed(2L);
    List<Class<?>> runTwo = chargeNTimes(gateway, 50);

    assertThat(runTwo).isNotEqualTo(runOne);
  }

  private List<Class<?>> chargeNTimes(MockPaymentGateway gateway, int n) {
    return java.util.stream.IntStream.range(0, n)
        .<Class<?>>mapToObj(i -> gateway.charge().getClass())
        .toList();
  }
}
