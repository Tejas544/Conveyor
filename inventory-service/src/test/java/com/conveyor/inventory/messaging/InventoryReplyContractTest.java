package com.conveyor.inventory.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.conveyor.common.envelope.ConveyorEnvelope;
import com.conveyor.common.kafka.KafkaTopics;
import com.conveyor.common.outbox.OutboxPoller;
import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.contracts.events.InventoryItemPayload;
import com.conveyor.contracts.events.ReleaseInventoryPayload;
import com.conveyor.contracts.events.ReserveInventoryPayload;
import com.conveyor.contracts.schema.SchemaValidator;
import com.conveyor.inventory.domain.Reservation;
import com.conveyor.inventory.domain.ReservationStatus;
import com.conveyor.inventory.domain.StockItem;
import com.conveyor.inventory.outbox.OutboxRecordRepository;
import com.conveyor.inventory.repository.ReservationRepository;
import com.conveyor.inventory.repository.StockItemRepository;
import com.conveyor.inventory.testsupport.TestKafkaConsumers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.ValidationMessage;
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
 * ADR-6: every producer contract test validates its emitted event against the published JSON Schema
 * in conveyor-contracts. Drives the whole pipeline for real — a command published onto {@code
 * conveyor.inventory.commands.v1}, consumed by {@link InventoryCommandListener}, replied to on
 * {@code conveyor.saga.replies.v1} via the outbox — for all three of PLAN.md Phase 4's reply
 * events.
 */
class InventoryReplyContractTest extends AbstractIntegrationTest {

  @Autowired private KafkaTemplate<String, String> kafkaTemplate;
  @Autowired private OutboxPoller outboxPoller;
  @Autowired private OutboxRecordRepository outboxRecordRepository;
  @Autowired private StockItemRepository stockItemRepository;
  @Autowired private ReservationRepository reservationRepository;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void reserveInventorySucceedsAndInventoryReservedValidatesAgainstItsSchema() throws Exception {
    String sku = "SKU-CONTRACT-RESERVED";
    stockItemRepository.saveAndFlush(new StockItem(sku, 10, 0, 2));
    UUID orderId = UUID.randomUUID();

    publishReserveCommand(orderId, List.of(new InventoryItemPayload(sku, 2)));

    String reply = awaitReply(orderId, "InventoryReserved");
    assertSchemaValid(reply, "envelope.schema.json");
    assertSchemaValid(reply, "inventory-reserved.schema.json");
  }

  @Test
  void reserveInventoryWithInsufficientStockProducesAValidInventoryReservationFailed()
      throws Exception {
    String sku = "SKU-CONTRACT-FAILED";
    stockItemRepository.saveAndFlush(new StockItem(sku, 1, 0, 1));
    UUID orderId = UUID.randomUUID();

    publishReserveCommand(orderId, List.of(new InventoryItemPayload(sku, 5)));

    String reply = awaitReply(orderId, "InventoryReservationFailed");
    assertSchemaValid(reply, "envelope.schema.json");
    assertSchemaValid(reply, "inventory-reservation-failed.schema.json");
  }

  @Test
  void releaseInventoryProducesAValidInventoryReleased() throws Exception {
    String sku = "SKU-CONTRACT-RELEASED";
    stockItemRepository.saveAndFlush(new StockItem(sku, 10, 3, 2));
    UUID orderId = UUID.randomUUID();
    Reservation reservation =
        reservationRepository.saveAndFlush(
            new Reservation(UUID.randomUUID(), orderId, sku, 3, ReservationStatus.HELD));

    publishReleaseCommand(orderId, List.of(reservation.getId()));

    String reply = awaitReply(orderId, "InventoryReleased");
    assertSchemaValid(reply, "envelope.schema.json");
    assertSchemaValid(reply, "inventory-released.schema.json");
  }

  private void publishReserveCommand(UUID orderId, List<InventoryItemPayload> items)
      throws Exception {
    ConveyorEnvelope<ReserveInventoryPayload> envelope =
        ConveyorEnvelope.of(
            ReserveInventoryPayload.EVENT_TYPE,
            ReserveInventoryPayload.SCHEMA_VERSION,
            "saga-orchestrator",
            null,
            orderId,
            null,
            new ReserveInventoryPayload(items));
    kafkaTemplate
        .send(
            KafkaTopics.INVENTORY_COMMANDS,
            orderId.toString(),
            objectMapper.writeValueAsString(envelope))
        .get();
  }

  private void publishReleaseCommand(UUID orderId, List<UUID> reservationIds) throws Exception {
    ConveyorEnvelope<ReleaseInventoryPayload> envelope =
        ConveyorEnvelope.of(
            ReleaseInventoryPayload.EVENT_TYPE,
            ReleaseInventoryPayload.SCHEMA_VERSION,
            "saga-orchestrator",
            null,
            orderId,
            null,
            new ReleaseInventoryPayload(reservationIds));
    kafkaTemplate
        .send(
            KafkaTopics.INVENTORY_COMMANDS,
            orderId.toString(),
            objectMapper.writeValueAsString(envelope))
        .get();
  }

  /**
   * Waits for the consumer to have processed the command (outbox row present), then publishes it.
   */
  private String awaitReply(UUID orderId, String expectedEventType) throws Exception {
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
        TestKafkaConsumers.subscribedTo(REDPANDA.getBootstrapServers(), KafkaTopics.SAGA_REPLIES)) {
      var records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10));
      for (var record : records) {
        if (record.key().equals(orderId.toString())) {
          return record.value();
        }
      }
      throw new AssertionError("No reply observed for order " + orderId);
    }
  }

  private void assertSchemaValid(String json, String schemaFileName) {
    Set<ValidationMessage> errors = SchemaValidator.validate(schemaFileName, json);
    assertThat(errors).as("%s errors: %s", schemaFileName, errors).isEmpty();
  }
}
