package com.conveyor.common.chaos;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Registers {@link ChaosGate} for every service that depends on conveyor-common. It is a harmless
 * no-op in every profile by default — the bean exists but {@code crash-at}/{@code delay-at} are
 * unset, so nothing fires. The one thing this configuration refuses outright is starting with chaos
 * injection actually <em>armed</em> while the {@code prod} profile is active (ARCHITECTURE.md §14,
 * §10.3): that specific combination is the one that would matter, and failing application startup
 * on it is deliberate — an env var typo should not be able to arm fault injection in production.
 */
@AutoConfiguration
@EnableConfigurationProperties(ChaosProperties.class)
public class ChaosAutoConfiguration {

  @Bean
  public ChaosGate chaosGate(ChaosProperties properties, Environment environment) {
    boolean isProd = java.util.Arrays.asList(environment.getActiveProfiles()).contains("prod");
    boolean isArmed =
        (properties.crashAt() != null && !properties.crashAt().isBlank())
            || (properties.delayAt() != null && !properties.delayAt().isBlank());

    if (isProd && isArmed) {
      throw new IllegalStateException(
          "Refusing to start: chaos injection is configured "
              + "(conveyor.chaos.crash-at/delay-at) while the 'prod' profile is active. "
              + "See ARCHITECTURE.md §14.");
    }

    return new ChaosGate(properties);
  }
}
