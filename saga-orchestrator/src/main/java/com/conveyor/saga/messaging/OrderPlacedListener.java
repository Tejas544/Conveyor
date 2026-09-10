package com.conveyor.saga.messaging;

import com.conveyor.common.chaos.ChaosGate;
import com.conveyor.common.kafka.KafkaTopics;
import com.conveyor.contracts.events.OrderItemPayload;
import com.conveyor.contracts.events.OrderPlacedPayload;
import com.conveyor.saga.service.SagaOrchestrationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * ARCHITECTURE.md §8.1: consumes {@code OrderPlaced} from {@code conveyor.order.events.v1} and
 * starts the saga. Also consumes its own {@code OrderConfirmed}/{@code OrderCancelled} output on
 * that same topic — {@link #onMessage} ignores anything that isn't {@code OrderPlaced}, the same
 * pattern order-service's {@code SagaEventProjectionListener} uses for the reverse direction.
 */
@Component
public class OrderPlacedListener {

  private static final Logger log = LoggerFactory.getLogger(OrderPlacedListener.class);
  private static final String CHAOS_POINT_AFTER_STATE_WRITE_BEFORE_COMMAND =
      "saga.after-state-write-before-command";

  private final SagaOrchestrationService orchestrationService;
  private final ObjectMapper objectMapper;
  private final ChaosGate chaosGate;

  public OrderPlacedListener(
      SagaOrchestrationService orchestrationService,
      ObjectMapper objectMapper,
      ChaosGate chaosGate) {
    this.orchestrationService = orchestrationService;
    this.objectMapper = objectMapper;
    this.chaosGate = chaosGate;
  }

  @KafkaListener(topics = KafkaTopics.ORDER_EVENTS, groupId = "saga-orchestrator")
  public void onMessage(String message) throws Exception {
    JsonNode envelope = objectMapper.readTree(message);
    String eventType = envelope.path("eventType").asText(null);
    if (!OrderPlacedPayload.EVENT_TYPE.equals(eventType)) {
      log.debug("Ignoring {} on {}", eventType, KafkaTopics.ORDER_EVENTS);
      return;
    }

    UUID eventId = UUID.fromString(envelope.path("eventId").asText());
    UUID orderId = UUID.fromString(envelope.path("orderId").asText());
    JsonNode payload = envelope.path("payload");

    List<OrderItemPayload> items =
        objectMapper.convertValue(
            payload.path("items"), new TypeReference<List<OrderItemPayload>>() {});
    BigDecimal totalAmount = new BigDecimal(payload.path("totalAmount").asText());
    String currency = payload.path("currency").asText();
    String paymentMethodToken = payload.path("paymentMethodToken").asText();

    orchestrationService.startSaga(
        eventId, orderId, items, totalAmount, currency, paymentMethodToken);
    chaosGate.maybeCrash(CHAOS_POINT_AFTER_STATE_WRITE_BEFORE_COMMAND);
  }
}
