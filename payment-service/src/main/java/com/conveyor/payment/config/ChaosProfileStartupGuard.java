package com.conveyor.payment.config;

import java.util.Arrays;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * ARCHITECTURE.md §10.4: {@code POST /test/failure-mode} is a test-only surface, active only under
 * the {@code chaos} Spring profile, and must refuse to start if {@code prod} is active alongside it
 * — an accidental profile combination should not be able to leave a fault-injection control panel
 * reachable in production. Unconditional (no {@code @Profile}) so it evaluates in every profile
 * combination, not just {@code chaos}.
 */
@Component
public class ChaosProfileStartupGuard implements InitializingBean {

  private final Environment environment;

  public ChaosProfileStartupGuard(Environment environment) {
    this.environment = environment;
  }

  @Override
  public void afterPropertiesSet() {
    var profiles = Arrays.asList(environment.getActiveProfiles());
    if (profiles.contains("chaos") && profiles.contains("prod")) {
      throw new IllegalStateException(
          "Refusing to start: the 'chaos' profile (which exposes POST /test/failure-mode) must "
              + "never be active alongside 'prod'. See ARCHITECTURE.md §10.4.");
    }
  }
}
