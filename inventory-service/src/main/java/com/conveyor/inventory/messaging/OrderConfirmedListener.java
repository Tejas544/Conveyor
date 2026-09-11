package com.conveyor.inventory.messaging;

import com.conveyor.common.kafka.KafkaTopics;
import com.conveyor.contracts.events.OrderConfirmedPayload;
import com.conveyor.inventory.service.InventoryReservationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * ARCHITECTURE.md §13 (INV-ORD-01), BUG-0021: consumes {@code OrderConfirmed} from {@code
 * conveyor.order.events.v1} — the same broadcast topic dispatch-service already reacts to — and
 * commits the order's held reservations. {@code OrderPlaced} and {@code OrderCancelled} on the same
 * topic are ignored here, the standard "unrecognized eventType is a harmless no-op" pattern.
 */
@Component
public class OrderConfirmedListener {

  private static final Logger log = LoggerFactory.getLogger(OrderConfirmedListener.class);

  private final InventoryReservationService reservationService;
  private final ObjectMapper objectMapper;

  public OrderConfirmedListener(
      InventoryReservationService reservationService, ObjectMapper objectMapper) {
    this.reservationService = reservationService;
    this.objectMapper = objectMapper;
  }

  @KafkaListener(topics = KafkaTopics.ORDER_EVENTS, groupId = "inventory-service")
  public void onMessage(String message) throws Exception {
    JsonNode envelope = objectMapper.readTree(message);
    String eventType = envelope.path("eventType").asText(null);
    if (!OrderConfirmedPayload.EVENT_TYPE.equals(eventType)) {
      log.debug("Ignoring {} on {}", eventType, KafkaTopics.ORDER_EVENTS);
      return;
    }

    UUID eventId = UUID.fromString(envelope.path("eventId").asText());
    UUID orderId = UUID.fromString(envelope.path("orderId").asText());
    reservationService.handleOrderConfirmed(eventId, orderId);
  }
}
