package com.conveyor.dispatch.messaging;

import com.conveyor.common.chaos.ChaosGate;
import com.conveyor.common.kafka.KafkaTopics;
import com.conveyor.contracts.events.OrderConfirmedPayload;
import com.conveyor.dispatch.service.DispatchService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * ARCHITECTURE.md §7.3, §8.1, §14: consumes {@code OrderConfirmed} — the saga's pivot event — from
 * {@code conveyor.order.events.v1}, which also carries {@code OrderPlaced} (order-service) and
 * {@code OrderCancelled} (saga-orchestrator); both are ignored here, the same "unrecognized
 * eventType is a harmless no-op" pattern every consumer in this project uses.
 */
@Component
public class OrderConfirmedListener {

  private static final Logger log = LoggerFactory.getLogger(OrderConfirmedListener.class);
  private static final String CHAOS_POINT_AFTER_SHIPMENT_BEFORE_NOTIFY =
      "dispatch.after-shipment-before-notify";

  private final DispatchService dispatchService;
  private final ObjectMapper objectMapper;
  private final ChaosGate chaosGate;

  public OrderConfirmedListener(
      DispatchService dispatchService, ObjectMapper objectMapper, ChaosGate chaosGate) {
    this.dispatchService = dispatchService;
    this.objectMapper = objectMapper;
    this.chaosGate = chaosGate;
  }

  @KafkaListener(topics = KafkaTopics.ORDER_EVENTS, groupId = "dispatch-service")
  public void onMessage(String message) throws Exception {
    JsonNode envelope = objectMapper.readTree(message);
    String eventType = envelope.path("eventType").asText(null);
    if (!OrderConfirmedPayload.EVENT_TYPE.equals(eventType)) {
      log.debug("Ignoring {} on {}", eventType, KafkaTopics.ORDER_EVENTS);
      return;
    }

    UUID eventId = UUID.fromString(envelope.path("eventId").asText());
    UUID orderId = UUID.fromString(envelope.path("orderId").asText());
    UUID sagaId =
        envelope.hasNonNull("sagaId") ? UUID.fromString(envelope.get("sagaId").asText()) : null;
    Instant confirmedAt = Instant.parse(envelope.path("payload").path("confirmedAt").asText());

    dispatchService.recordShipment(eventId, orderId, sagaId);
    // A crash here — real (ChaosGate) or genuine — leaves the shipment committed and no
    // notification written; redelivery resumes at exactly this point, since recordShipment is a
    // no-op the second time and writeNotification is unconditionally safe to redo.
    chaosGate.maybeCrash(CHAOS_POINT_AFTER_SHIPMENT_BEFORE_NOTIFY);
    dispatchService.writeNotification(orderId, confirmedAt);
  }
}
