package com.conveyor.dispatch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.conveyor.common.envelope.ConveyorEnvelope;
import com.conveyor.common.kafka.KafkaTopics;
import com.conveyor.common.outbox.OutboxPoller;
import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.contracts.events.OrderConfirmedPayload;
import com.conveyor.dispatch.domain.Shipment;
import com.conveyor.dispatch.notification.NotificationDocument;
import com.conveyor.dispatch.notification.NotificationRepository;
import com.conveyor.dispatch.outbox.OutboxRecordRepository;
import com.conveyor.dispatch.repository.ShipmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * PLAN.md Phase 7: "full E2E — REST call → shipment row → notification document → ShipmentCreated
 * on the topic." The REST call itself (order-service, saga-orchestrator) is exercised by the top-
 * level {@code e2e} module against the whole stack; this test drives dispatch-service directly by
 * publishing the {@code OrderConfirmed} event the saga-orchestrator produces at the pivot
 * (ARCHITECTURE.md §8.1) — the same "exercised by driving it directly" approach every phase before
 * its collaborators existed has used (PLAN.md).
 */
class DispatchHappyPathIntegrationTest extends AbstractIntegrationTest {

  @Autowired private KafkaTemplate<String, String> kafkaTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private ShipmentRepository shipmentRepository;
  @Autowired private NotificationRepository notificationRepository;
  @Autowired private OutboxRecordRepository outboxRecordRepository;
  @Autowired private OutboxPoller outboxPoller;

  @Test
  void orderConfirmedCreatesAShipmentAndANotification() throws Exception {
    UUID orderId = UUID.randomUUID();
    publishOrderConfirmed(orderId, Instant.now());

    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(() -> assertThat(shipmentRepository.findByOrderId(orderId)).isPresent());

    Shipment shipment = shipmentRepository.findByOrderId(orderId).orElseThrow();
    assertThat(shipment.getCarrier()).isNotBlank();
    assertThat(shipment.getTrackingNumber()).isNotBlank();
    assertThat(shipment.getStatus()).isEqualTo("CREATED");

    assertThat(
            outboxRecordRepository.findAll().stream()
                .anyMatch(
                    r ->
                        r.getAggregateId().equals(orderId.toString())
                            && r.getEventType().equals("ShipmentCreated")))
        .isTrue();
    outboxPoller.publishOneBatch();

    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () -> assertThat(notificationRepository.findByOrderId(orderId.toString())).hasSize(1));

    List<NotificationDocument> notifications =
        notificationRepository.findByOrderId(orderId.toString());
    assertThat(notifications.get(0).getChannel()).isEqualTo("EMAIL");
    assertThat(notifications.get(0).getStatus()).isEqualTo("SENT");
  }

  private void publishOrderConfirmed(UUID orderId, Instant confirmedAt) throws Exception {
    ConveyorEnvelope<OrderConfirmedPayload> envelope =
        ConveyorEnvelope.of(
            OrderConfirmedPayload.EVENT_TYPE,
            OrderConfirmedPayload.SCHEMA_VERSION,
            "saga-orchestrator",
            UUID.randomUUID(),
            orderId,
            null,
            new OrderConfirmedPayload(orderId, confirmedAt));
    kafkaTemplate
        .send(
            KafkaTopics.ORDER_EVENTS, orderId.toString(), objectMapper.writeValueAsString(envelope))
        .get();
  }
}
