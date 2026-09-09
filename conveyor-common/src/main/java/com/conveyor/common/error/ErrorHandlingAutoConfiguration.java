package com.conveyor.common.error;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;

/** Registers {@link ProblemDetailAdvice} for every service that depends on conveyor-common. */
@AutoConfiguration(after = WebMvcAutoConfiguration.class)
public class ErrorHandlingAutoConfiguration {

  @Bean
  public ProblemDetailAdvice problemDetailAdvice() {
    return new ProblemDetailAdvice();
  }
}
