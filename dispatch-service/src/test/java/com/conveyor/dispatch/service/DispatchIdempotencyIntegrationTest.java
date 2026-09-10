package com.conveyor.dispatch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.conveyor.common.envelope.ConveyorEnvelope;
import com.conveyor.common.kafka.KafkaTopics;
import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.contracts.events.OrderConfirmedPayload;
import com.conveyor.dispatch.notification.NotificationRepository;
import com.conveyor.dispatch.outbox.OutboxRecordRepository;
import com.conveyor.dispatch.repository.ShipmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * PLAN.md Phase 7: "dispatch is idempotent — redelivered OrderConfirmed → one shipment, one
 * notification." Publishes the exact same envelope (same {@code eventId}) twice.
 */
class DispatchIdempotencyIntegrationTest extends AbstractIntegrationTest {

  @Autowired private KafkaTemplate<String, String> kafkaTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private ShipmentRepository shipmentRepository;
  @Autowired private NotificationRepository notificationRepository;
  @Autowired private OutboxRecordRepository outboxRecordRepository;

  @Test
  void redeliveredOrderConfirmedProducesExactlyOneShipmentAndOneNotification() throws Exception {
    UUID orderId = UUID.randomUUID();
    Instant confirmedAt = Instant.now();
    ConveyorEnvelope<OrderConfirmedPayload> envelope =
        ConveyorEnvelope.of(
            OrderConfirmedPayload.EVENT_TYPE,
            OrderConfirmedPayload.SCHEMA_VERSION,
            "saga-orchestrator",
            UUID.randomUUID(),
            orderId,
            null,
            new OrderConfirmedPayload(orderId, confirmedAt));
    String json = objectMapper.writeValueAsString(envelope);

    // Same eventId both times — a genuine redelivery, not two different messages.
    kafkaTemplate.send(KafkaTopics.ORDER_EVENTS, orderId.toString(), json).get();
    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(() -> assertThat(shipmentRepository.findByOrderId(orderId)).isPresent());
    kafkaTemplate.send(KafkaTopics.ORDER_EVENTS, orderId.toString(), json).get();

    // No second listener invocation to synchronize on directly — give the redelivery time to be
    // (harmlessly) processed before asserting nothing duplicated.
    Thread.sleep(2_000);

    assertThat(shipmentRepository.findAll().stream().filter(s -> s.getOrderId().equals(orderId)))
        .hasSize(1);
    assertThat(notificationRepository.findByOrderId(orderId.toString())).hasSize(1);
    assertThat(
            outboxRecordRepository.findAll().stream()
                .filter(
                    r ->
                        r.getAggregateId().equals(orderId.toString())
                            && r.getEventType().equals("ShipmentCreated")))
        .hasSize(1);
  }
}
