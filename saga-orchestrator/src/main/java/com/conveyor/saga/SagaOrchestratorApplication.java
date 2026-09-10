package com.conveyor.saga;

import com.conveyor.saga.config.SagaProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(SagaProperties.class)
public class SagaOrchestratorApplication {

  public static void main(String[] args) {
    SpringApplication.run(SagaOrchestratorApplication.class, args);
  }
}
