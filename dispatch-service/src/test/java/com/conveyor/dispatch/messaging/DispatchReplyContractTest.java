package com.conveyor.dispatch.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.conveyor.common.envelope.ConveyorEnvelope;
import com.conveyor.common.kafka.KafkaTopics;
import com.conveyor.common.outbox.OutboxPoller;
import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.contracts.events.OrderConfirmedPayload;
import com.conveyor.contracts.schema.SchemaValidator;
import com.conveyor.dispatch.outbox.OutboxRecordRepository;
import com.conveyor.dispatch.testsupport.TestKafkaConsumers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.ValidationMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * ADR-6: {@code ShipmentCreated} validates against its published JSON Schema — drives the whole
 * pipeline for real, the same pattern {@code InventoryReplyContractTest} and {@code
 * PaymentReplyContractTest} use.
 */
class DispatchReplyContractTest extends AbstractIntegrationTest {

  @Autowired private KafkaTemplate<String, String> kafkaTemplate;
  @Autowired private OutboxPoller outboxPoller;
  @Autowired private OutboxRecordRepository outboxRecordRepository;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void orderConfirmedProducesAValidShipmentCreated() throws Exception {
    UUID orderId = UUID.randomUUID();

    ConveyorEnvelope<OrderConfirmedPayload> envelope =
        ConveyorEnvelope.of(
            OrderConfirmedPayload.EVENT_TYPE,
            OrderConfirmedPayload.SCHEMA_VERSION,
            "saga-orchestrator",
            UUID.randomUUID(),
            orderId,
            null,
            new OrderConfirmedPayload(orderId, Instant.now()));
    kafkaTemplate
        .send(
            KafkaTopics.ORDER_EVENTS, orderId.toString(), objectMapper.writeValueAsString(envelope))
        .get();

    String reply = awaitReply(orderId);
    assertSchemaValid(reply, "envelope.schema.json");
    assertSchemaValid(reply, "shipment-created.schema.json");
  }

  private String awaitReply(UUID orderId) throws Exception {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      boolean present =
          outboxRecordRepository.findAll().stream()
              .anyMatch(
                  r ->
                      r.getAggregateId().equals(orderId.toString())
                          && r.getEventType().equals("ShipmentCreated"));
      if (present) {
        break;
      }
      Thread.sleep(100);
    }

    outboxPoller.publishOneBatch();

    try (Consumer<String, String> consumer =
        TestKafkaConsumers.subscribedTo(
            REDPANDA.getBootstrapServers(), KafkaTopics.DISPATCH_EVENTS)) {
      var records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10));
      for (var record : records) {
        if (record.key().equals(orderId.toString())) {
          return record.value();
        }
      }
      throw new AssertionError("No ShipmentCreated observed for order " + orderId);
    }
  }

  private void assertSchemaValid(String json, String schemaFileName) {
    Set<ValidationMessage> errors = SchemaValidator.validate(schemaFileName, json);
    assertThat(errors).as("%s errors: %s", schemaFileName, errors).isEmpty();
  }
}
