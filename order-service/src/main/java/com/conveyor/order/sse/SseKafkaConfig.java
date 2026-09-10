package com.conveyor.order.sse;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ADR-10: each Order Service replica joins a unique, ephemeral consumer group so every replica —
 * not just whichever one happened to consume a given Kafka message — can broadcast it to its own
 * connected browsers. {@code HOSTNAME} is a container's own hostname in Docker/K8s (effectively
 * unique per replica); falls back to a random suffix for a bare local run.
 */
@Configuration
public class SseKafkaConfig {

  @Bean
  public String sseConsumerGroupId(@Value("${HOSTNAME:}") String hostname) {
    String suffix =
        (hostname == null || hostname.isBlank()) ? UUID.randomUUID().toString() : hostname;
    return "sse-fanout-" + suffix;
  }
}
