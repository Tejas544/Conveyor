package com.conveyor.order.sse;

import com.conveyor.common.kafka.KafkaTopics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * ARCHITECTURE.md §10.1's SSE event catalogue, sourced from the same topics {@link
 * com.conveyor.order.messaging.SagaEventProjectionListener} already consumes for the {@code
 * orders.status} projection, plus dispatch's events for {@code order.shipment}. Deliberately
 * <em>not</em> inbox-deduplicated like every other consumer in this codebase (§9): this listener
 * only ever broadcasts to a live browser tab, so an occasional duplicate frame during redelivery is
 * a harmless double-render, not a correctness issue worth a database write per message.
 *
 * <p>The payload shape here is simpler than ARCHITECTURE.md §10.1's illustrative {@code order.step}
 * example ({@code step}/{@code direction}/{@code status} fields) — those are
 * saga-orchestrator-internal concepts this service has no authoritative source for from reply
 * events alone. Instead every SSE event carries the envelope's own {@code eventType} plus its raw
 * {@code payload}, which the frontend maps to a step label. Recorded here rather than silently
 * diverging from the doc.
 */
@Component
public class SseBroadcastListener {

  private final SseBroadcaster broadcaster;
  private final ObjectMapper objectMapper;

  public SseBroadcastListener(SseBroadcaster broadcaster, ObjectMapper objectMapper) {
    this.broadcaster = broadcaster;
    this.objectMapper = objectMapper;
  }

  @KafkaListener(
      topics = {KafkaTopics.ORDER_EVENTS, KafkaTopics.SAGA_REPLIES, KafkaTopics.DISPATCH_EVENTS},
      groupId = "#{@sseConsumerGroupId}",
      properties = {"auto.offset.reset:latest"})
  public void onMessage(String message) throws Exception {
    JsonNode envelope = objectMapper.readTree(message);
    String eventType = envelope.path("eventType").asText(null);
    String sseEventName = mapToSseEvent(eventType);
    if (sseEventName == null) {
      return;
    }

    String orderId = envelope.path("orderId").asText(null);
    if (orderId == null) {
      return;
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("orderId", orderId);
    data.put("sagaId", envelope.path("sagaId").asText(null));
    data.put("eventType", eventType);
    data.put("occurredAt", envelope.path("occurredAt").asText(null));
    data.put("payload", objectMapper.convertValue(envelope.path("payload"), Map.class));

    broadcaster.publish(sseEventName, orderId, data);
  }

  private String mapToSseEvent(String eventType) {
    if (eventType == null) {
      return null;
    }
    return switch (eventType) {
      case "OrderPlaced" -> "order.placed";
      case "OrderConfirmed" -> "order.confirmed";
      case "OrderCancelled" -> "order.cancelled";
      case "ShipmentCreated" -> "order.shipment";
      case "InventoryReserved",
          "InventoryReservationFailed",
          "InventoryReleased",
          "PaymentCharged",
          "PaymentFailed",
          "PaymentRefunded" ->
          "order.step";
      default -> null;
    };
  }
}
