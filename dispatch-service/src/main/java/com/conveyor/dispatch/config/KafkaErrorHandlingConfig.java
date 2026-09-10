package com.conveyor.dispatch.config;

import com.conveyor.common.kafka.KafkaTopics;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * ARCHITECTURE.md §6.2, §11, §14: dispatch's listener retries a failing message a bounded number of
 * times, then republishes it to {@code <topic>.dlq} and moves on — PLAN.md Phase 7's "dispatch
 * failing repeatedly does not cancel the order; it retries and DLQs." This is deliberately scoped
 * to dispatch-service alone (not conveyor-common): dispatch is the one consumer in this project
 * whose failure has no saga-level compensation to fall back on, so "give up and surface it" is the
 * correct terminal behaviour here in a way it is not for inventory/payment/saga-orchestrator, whose
 * own failure handling is the saga timeout/compensation machinery instead.
 */
@Configuration
public class KafkaErrorHandlingConfig {

  /** {@code <topic>.dlq} per §6.2's convention: 1 partition, so recovery can always target it. */
  @Bean
  public NewTopic orderEventsDlqTopic() {
    return TopicBuilder.name(KafkaTopics.deadLetterTopic(KafkaTopics.ORDER_EVENTS))
        .partitions(1)
        .replicas(KafkaTopics.LOCAL_REPLICATION_FACTOR)
        .build();
  }

  @Bean
  public DefaultErrorHandler dispatchErrorHandler(
      KafkaTemplate<String, String> kafkaTemplate, MeterRegistry meterRegistry) {
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(
            kafkaTemplate,
            (record, ex) -> {
              meterRegistry
                  .counter("conveyor_dlq_messages_total", "topic", record.topic())
                  .increment();
              return new TopicPartition(KafkaTopics.deadLetterTopic(record.topic()), 0);
            });
    // 1 initial attempt + 2 retries, 300ms apart, before giving up and publishing to the DLQ.
    DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(300L, 2L));
    handler.addNotRetryableExceptions(IllegalArgumentException.class);
    return handler;
  }
}
