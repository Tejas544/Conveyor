package com.conveyor.common.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Registers {@link EnvelopeMdcRecordInterceptor} as the single {@code RecordInterceptor<String,
 * String>} bean; Spring Boot's Kafka autoconfiguration wires any such bean into the autoconfigured
 * {@code ConcurrentKafkaListenerContainerFactory} automatically, so individual services do not need
 * to configure this themselves.
 */
@AutoConfiguration(after = KafkaAutoConfiguration.class)
public class KafkaMdcAutoConfiguration {

  @Bean
  public EnvelopeMdcRecordInterceptor envelopeMdcRecordInterceptor(ObjectMapper objectMapper) {
    return new EnvelopeMdcRecordInterceptor(objectMapper);
  }
}
