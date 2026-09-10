package com.conveyor.dispatch.testsupport;

import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/** A raw, non-Spring-managed consumer for tests to assert what actually landed on a topic. */
public final class TestKafkaConsumers {

  private TestKafkaConsumers() {}

  public static Consumer<String, String> subscribedTo(String bootstrapServers, String topic) {
    Map<String, Object> props =
        KafkaTestUtils.consumerProps(bootstrapServers, "test-" + topic, "true");
    Consumer<String, String> consumer =
        new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
            .createConsumer();
    consumer.subscribe(java.util.List.of(topic));
    return consumer;
  }
}
