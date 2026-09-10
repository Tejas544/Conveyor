package com.conveyor.dispatch.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.conveyor.common.envelope.ConveyorEnvelope;
import com.conveyor.common.kafka.KafkaTopics;
import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.contracts.events.OrderConfirmedPayload;
import com.conveyor.dispatch.repository.ShipmentRepository;
import com.conveyor.dispatch.testsupport.TestKafkaConsumers;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * PLAN.md Phase 7: "dispatch failing repeatedly does not cancel the order (the pivot rule holds);
 * it retries and DLQs, and the order stays CONFIRMED." A message with an unparseable {@code
 * confirmedAt} fails the same way on every attempt — a poison message, not a transient one — so it
 * is retried the configured number of times and then republished to {@code
 * conveyor.order.events.v1.dlq} rather than wedging the consumer forever. Both records are pinned
 * to partition 0 so the second assertion actually proves the poison record did not block its
 * partition, rather than merely landing on an different, unaffected one.
 *
 * <p>Dispatch never talks back to order-service or saga-orchestrator — it only ever consumes — so
 * "the order stays CONFIRMED" is true here by construction: nothing this test does is capable of
 * un-confirming an order. What the test actually demonstrates is the half of that claim dispatch
 * *is* responsible for: its own failure is bounded and does not stop it from processing the next
 * order.
 */
class DispatchRetryAndDlqIntegrationTest extends AbstractIntegrationTest {

  @Autowired private KafkaTemplate<String, String> kafkaTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private ShipmentRepository shipmentRepository;
  @Autowired private MeterRegistry meterRegistry;

  @Test
  void aPoisonMessageIsRetriedThenDlqdWithoutBlockingTheNextOrder() throws Exception {
    UUID poisonOrderId = UUID.randomUUID();
    UUID poisonEventId = UUID.randomUUID();
    String poisonJson =
        objectMapper.writeValueAsString(
            Map.of(
                "eventId", poisonEventId.toString(),
                "eventType", OrderConfirmedPayload.EVENT_TYPE,
                "schemaVersion", 1,
                "occurredAt", Instant.now().toString(),
                "producer", "saga-orchestrator",
                "orderId", poisonOrderId.toString(),
                "correlationId", poisonOrderId.toString(),
                "payload",
                    Map.of("orderId", poisonOrderId.toString(), "confirmedAt", "not-a-date")));

    kafkaTemplate
        .send(
            new ProducerRecord<>(KafkaTopics.ORDER_EVENTS, 0, poisonOrderId.toString(), poisonJson))
        .get();

    try (Consumer<String, String> dlqConsumer =
        TestKafkaConsumers.subscribedTo(
            REDPANDA.getBootstrapServers(),
            KafkaTopics.deadLetterTopic(KafkaTopics.ORDER_EVENTS))) {
      var dlqRecords = KafkaTestUtils.getRecords(dlqConsumer, Duration.ofSeconds(20));
      boolean found = false;
      for (var record : dlqRecords) {
        if (poisonOrderId.toString().equals(record.key())) {
          found = true;
        }
      }
      assertThat(found).as("poison message observed on the DLQ topic").isTrue();
    }

    assertThat(shipmentRepository.findByOrderId(poisonOrderId)).isEmpty();
    assertThat(
            meterRegistry
                .counter("conveyor_dlq_messages_total", "topic", KafkaTopics.ORDER_EVENTS)
                .count())
        .isGreaterThanOrEqualTo(1.0);

    UUID goodOrderId = UUID.randomUUID();
    ConveyorEnvelope<OrderConfirmedPayload> goodEnvelope =
        ConveyorEnvelope.of(
            OrderConfirmedPayload.EVENT_TYPE,
            OrderConfirmedPayload.SCHEMA_VERSION,
            "saga-orchestrator",
            UUID.randomUUID(),
            goodOrderId,
            null,
            new OrderConfirmedPayload(goodOrderId, Instant.now()));
    kafkaTemplate
        .send(
            new ProducerRecord<>(
                KafkaTopics.ORDER_EVENTS,
                0,
                goodOrderId.toString(),
                objectMapper.writeValueAsString(goodEnvelope)))
        .get();

    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(() -> assertThat(shipmentRepository.findByOrderId(goodOrderId)).isPresent());
  }
}
