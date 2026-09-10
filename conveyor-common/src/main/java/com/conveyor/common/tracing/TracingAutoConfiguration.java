package com.conveyor.common.tracing;

import io.micrometer.tracing.Tracer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * Registers {@link TraceparentSupport}; see that class's Javadoc for why not {@code @Component}.
 */
@AutoConfiguration
@ConditionalOnClass(Tracer.class)
public class TracingAutoConfiguration {

  @Bean
  public TraceparentSupport traceparentSupport(Tracer tracer) {
    return new TraceparentSupport(tracer);
  }
}
