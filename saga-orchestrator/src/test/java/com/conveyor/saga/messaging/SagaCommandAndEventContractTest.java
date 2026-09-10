package com.conveyor.saga.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.conveyor.common.envelope.ConveyorEnvelope;
import com.conveyor.common.kafka.KafkaTopics;
import com.conveyor.common.outbox.OutboxPoller;
import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.contracts.events.InventoryItemPayload;
import com.conveyor.contracts.events.InventoryReservationFailedPayload;
import com.conveyor.contracts.events.InventoryReservedPayload;
import com.conveyor.contracts.events.OrderItemPayload;
import com.conveyor.contracts.events.OrderPlacedPayload;
import com.conveyor.contracts.events.PaymentChargedPayload;
import com.conveyor.contracts.events.ShippingAddressPayload;
import com.conveyor.contracts.schema.SchemaValidator;
import com.conveyor.saga.outbox.OutboxRecordRepository;
import com.conveyor.saga.testsupport.TestKafkaConsumers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.ValidationMessage;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * ADR-6: drives the whole pipeline for real — {@code OrderPlaced} in, {@code ReserveInventory} out;
 * a simulated {@code InventoryReserved} reply in, {@code ChargePayment} out; a simulated {@code
 * PaymentCharged} reply in, {@code OrderConfirmed} out — validating every command and event
 * saga-orchestrator produces against its published JSON Schema, plus the {@code
 * InventoryReservationFailed} → {@code OrderCancelled} compensation path.
 */
class SagaCommandAndEventContractTest extends AbstractIntegrationTest {

  @Autowired private KafkaTemplate<String, String> kafkaTemplate;
  @Autowired private OutboxPoller outboxPoller;
  @Autowired private OutboxRecordRepository outboxRecordRepository;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void happyPathProducesSchemaValidCommandsAndTheConfirmedEvent() throws Exception {
    UUID orderId = UUID.randomUUID();
    publishOrderPlaced(orderId);

    String reserveCommand =
        awaitOutboxThenConsume(orderId, "ReserveInventory", KafkaTopics.INVENTORY_COMMANDS);
    assertValid(reserveCommand, "envelope.schema.json");
    assertValid(reserveCommand, "reserve-inventory.schema.json");
    UUID sagaId = UUID.fromString(objectMapper.readTree(reserveCommand).get("sagaId").asText());

    publishReply(
        orderId,
        InventoryReservedPayload.EVENT_TYPE,
        InventoryReservedPayload.SCHEMA_VERSION,
        sagaId,
        new InventoryReservedPayload(
            List.of(UUID.randomUUID()), List.of(new InventoryItemPayload("SKU-1", 2))));

    String chargeCommand =
        awaitOutboxThenConsume(orderId, "ChargePayment", KafkaTopics.PAYMENT_COMMANDS);
    assertValid(chargeCommand, "envelope.schema.json");
    assertValid(chargeCommand, "charge-payment.schema.json");

    publishReply(
        orderId,
        PaymentChargedPayload.EVENT_TYPE,
        PaymentChargedPayload.SCHEMA_VERSION,
        sagaId,
        new PaymentChargedPayload(UUID.randomUUID(), new BigDecimal("19.98"), "USD", "gw_ref"));

    String confirmedEvent =
        awaitOutboxThenConsume(orderId, "OrderConfirmed", KafkaTopics.ORDER_EVENTS);
    assertValid(confirmedEvent, "envelope.schema.json");
    assertValid(confirmedEvent, "order-confirmed.schema.json");
  }

  @Test
  void reservationFailureProducesASchemaValidOrderCancelled() throws Exception {
    UUID orderId = UUID.randomUUID();
    publishOrderPlaced(orderId);
    String reserveCommand =
        awaitOutboxThenConsume(orderId, "ReserveInventory", KafkaTopics.INVENTORY_COMMANDS);
    UUID sagaId = UUID.fromString(objectMapper.readTree(reserveCommand).get("sagaId").asText());

    publishReply(
        orderId,
        InventoryReservationFailedPayload.EVENT_TYPE,
        InventoryReservationFailedPayload.SCHEMA_VERSION,
        sagaId,
        new InventoryReservationFailedPayload(
            InventoryReservationFailedPayload.REASON_INSUFFICIENT_STOCK, List.of()));

    String cancelledEvent =
        awaitOutboxThenConsume(orderId, "OrderCancelled", KafkaTopics.ORDER_EVENTS);
    assertValid(cancelledEvent, "envelope.schema.json");
    assertValid(cancelledEvent, "order-cancelled.schema.json");
  }

  private void publishOrderPlaced(UUID orderId) throws Exception {
    OrderPlacedPayload payload =
        new OrderPlacedPayload(
            UUID.randomUUID(),
            List.of(new OrderItemPayload("SKU-1", 2, new BigDecimal("9.99"))),
            new BigDecimal("19.98"),
            "USD",
            new ShippingAddressPayload("1 Test St", "Testville", "00000", "IN"),
            "tok_test_visa");
    ConveyorEnvelope<OrderPlacedPayload> envelope =
        ConveyorEnvelope.of(
            OrderPlacedPayload.EVENT_TYPE,
            OrderPlacedPayload.SCHEMA_VERSION,
            "order-service",
            null,
            orderId,
            null,
            payload);
    kafkaTemplate
        .send(
            KafkaTopics.ORDER_EVENTS, orderId.toString(), objectMapper.writeValueAsString(envelope))
        .get();
  }

  private <T> void publishReply(
      UUID orderId, String eventType, int schemaVersion, UUID sagaId, T payload) throws Exception {
    ConveyorEnvelope<T> envelope =
        ConveyorEnvelope.of(
            eventType, schemaVersion, "test-harness", sagaId, orderId, null, payload);
    kafkaTemplate
        .send(
            KafkaTopics.SAGA_REPLIES, orderId.toString(), objectMapper.writeValueAsString(envelope))
        .get();
  }

  /**
   * Waits for saga-orchestrator's own outbox row, publishes it, then reads it back off the topic.
   */
  private String awaitOutboxThenConsume(UUID orderId, String expectedEventType, String topic)
      throws Exception {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      boolean present =
          outboxRecordRepository.findAll().stream()
              .anyMatch(
                  r ->
                      r.getAggregateId().equals(orderId.toString())
                          && r.getEventType().equals(expectedEventType));
      if (present) {
        break;
      }
      Thread.sleep(100);
    }

    outboxPoller.publishOneBatch();

    try (Consumer<String, String> consumer =
        TestKafkaConsumers.subscribedTo(REDPANDA.getBootstrapServers(), topic)) {
      var records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10));
      for (var record : records) {
        if (record.key().equals(orderId.toString())) {
          com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(record.value());
          if (expectedEventType.equals(node.path("eventType").asText())) {
            return record.value();
          }
        }
      }
      throw new AssertionError(
          "No " + expectedEventType + " observed on " + topic + " for order " + orderId);
    }
  }

  private void assertValid(String json, String schemaFileName) {
    Set<ValidationMessage> errors = SchemaValidator.validate(schemaFileName, json);
    assertThat(errors).as("%s errors: %s", schemaFileName, errors).isEmpty();
  }
}
