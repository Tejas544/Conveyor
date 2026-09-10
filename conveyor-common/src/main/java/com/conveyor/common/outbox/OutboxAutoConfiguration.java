package com.conveyor.common.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Registers {@link OutboxPoller} for every service that depends on conveyor-common and has a {@link
 * DataSource} and a Kafka producer on its classpath — every service in this project, but guarded
 * with {@code @ConditionalOnClass} rather than assumed, matching this module's other
 * auto-configurations.
 */
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@EnableConfigurationProperties(OutboxPollerProperties.class)
@ConditionalOnClass({DataSource.class, KafkaTemplate.class})
@EnableScheduling
public class OutboxAutoConfiguration {

  @Bean
  public OutboxPoller outboxPoller(
      NamedParameterJdbcTemplate jdbcTemplate,
      KafkaTemplate<String, String> kafkaTemplate,
      ObjectMapper objectMapper,
      OutboxPollerProperties properties,
      MeterRegistry meterRegistry) {
    OutboxPoller poller = new OutboxPoller(jdbcTemplate, kafkaTemplate, objectMapper, properties);
    meterRegistry.gauge(
        "conveyor_outbox_lag_seconds", poller, OutboxPoller::oldestUnpublishedLagSeconds);
    return poller;
  }
}
