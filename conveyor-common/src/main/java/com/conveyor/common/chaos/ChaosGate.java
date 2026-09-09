package com.conveyor.common.chaos;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Named fault-injection points, armed by environment variable (ARCHITECTURE.md §14). A no-op unless
 * {@code conveyor.chaos.crash-at} or {@code conveyor.chaos.delay-at} names the exact point being
 * evaluated — business code calls {@link #maybeCrash(String)} / {@link #maybeDelay(String)} at one
 * precise line, unconditionally, and this class decides whether anything happens. This is the
 * mechanism that makes a chaos trial reproducible: the kill lands at a controlled point, not
 * "somewhere in the middle."
 *
 * <p>Registration of this bean is refused outright under the {@code prod} profile by {@link
 * ChaosAutoConfiguration} — a fault-injection point cannot fire in production because the bean that
 * would fire it does not exist there.
 */
public class ChaosGate {

  private static final Logger log = LoggerFactory.getLogger(ChaosGate.class);
  private static final SecureRandom RANDOM = new SecureRandom();

  private final ChaosProperties properties;
  private final Map<String, Long> delaysByPoint = new ConcurrentHashMap<>();

  public ChaosGate(ChaosProperties properties) {
    this.properties = properties;
    if (properties.delayAt() != null && !properties.delayAt().isBlank()) {
      String[] parts = properties.delayAt().split("=", 2);
      if (parts.length == 2) {
        delaysByPoint.put(parts[0].trim(), Long.parseLong(parts[1].trim()));
      }
    }
  }

  /** True if {@code point} is the one named by {@code conveyor.chaos.crash-at}. */
  public boolean isArmedToCrash(String point) {
    return point.equals(properties.crashAt());
  }

  /**
   * Crashes the JVM (via {@link Runtime#halt(int)}, skipping shutdown hooks — the closest
   * in-process approximation of a SIGKILL) if {@code point} is armed and the configured probability
   * roll succeeds.
   */
  public void maybeCrash(String point) {
    if (!isArmedToCrash(point)) {
      return;
    }
    if (RANDOM.nextDouble() >= properties.crashProbability()) {
      return;
    }
    log.warn("ChaosGate: crashing at injection point [{}]", point);
    Runtime.getRuntime().halt(1);
  }

  /** Sleeps the calling thread if {@code point} has a configured delay. */
  public void maybeDelay(String point) {
    Long millis = delaysByPoint.get(point);
    if (millis == null || millis <= 0) {
      return;
    }
    log.warn("ChaosGate: delaying {} ms at injection point [{}]", millis, point);
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
