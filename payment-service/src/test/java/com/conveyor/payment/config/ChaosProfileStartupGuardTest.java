package com.conveyor.payment.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * PLAN.md Phase 5: the app fails to start with {@code chaos} + {@code prod} profiles both active.
 * Exercised directly against the guard (no full Spring context / database needed) since {@link
 * ChaosProfileStartupGuard#afterPropertiesSet()} running during bean initialization is exactly the
 * mechanism that prevents {@code ApplicationContext} startup from ever completing.
 */
class ChaosProfileStartupGuardTest {

  @Test
  void refusesToInitializeWithBothChaosAndProdActive() {
    MockEnvironment environment = new MockEnvironment();
    environment.addActiveProfile("chaos");
    environment.addActiveProfile("prod");
    ChaosProfileStartupGuard guard = new ChaosProfileStartupGuard(environment);

    assertThatThrownBy(guard::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void chaosAloneInitializesFine() {
    MockEnvironment environment = new MockEnvironment();
    environment.addActiveProfile("chaos");
    ChaosProfileStartupGuard guard = new ChaosProfileStartupGuard(environment);

    assertThatCode(guard::afterPropertiesSet).doesNotThrowAnyException();
  }

  @Test
  void prodAloneInitializesFine() {
    MockEnvironment environment = new MockEnvironment();
    environment.addActiveProfile("prod");
    ChaosProfileStartupGuard guard = new ChaosProfileStartupGuard(environment);

    assertThatCode(guard::afterPropertiesSet).doesNotThrowAnyException();
  }
}
