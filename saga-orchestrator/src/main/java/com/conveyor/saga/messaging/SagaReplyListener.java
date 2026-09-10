package com.conveyor.saga.messaging;

import com.conveyor.common.chaos.ChaosGate;
import com.conveyor.common.kafka.KafkaTopics;
import com.conveyor.contracts.events.InventoryItemPayload;
import com.conveyor.contracts.events.InventoryReleasedPayload;
import com.conveyor.contracts.events.InventoryReservationFailedPayload;
import com.conveyor.contracts.events.InventoryReservedPayload;
import com.conveyor.contracts.events.PaymentChargedPayload;
import com.conveyor.contracts.events.PaymentFailedPayload;
import com.conveyor.contracts.events.PaymentRefundedPayload;
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
 * ARCHITECTURE.md §8.1, §8.2: consumes every saga reply from {@code conveyor.saga.replies.v1} and
 * routes it to {@link SagaOrchestrationService}. The {@code saga.after-reply-before-state-write}
 * chaos point (§14) sits before dispatch — a crash here proves the message is safely redelivered
 * (never acknowledged, since the listener never returns) rather than silently lost.
 */
@Component
public class SagaReplyListener {

  private static final Logger log = LoggerFactory.getLogger(SagaReplyListener.class);
  private static final String CHAOS_POINT_AFTER_REPLY_BEFORE_STATE_WRITE =
      "saga.after-reply-before-state-write";
  private static final String CHAOS_POINT_AFTER_STATE_WRITE_BEFORE_COMMAND =
      "saga.after-state-write-before-command";

  private final SagaOrchestrationService orchestrationService;
  private final ObjectMapper objectMapper;
  private final ChaosGate chaosGate;

  public SagaReplyListener(
      SagaOrchestrationService orchestrationService,
      ObjectMapper objectMapper,
      ChaosGate chaosGate) {
    this.orchestrationService = orchestrationService;
    this.objectMapper = objectMapper;
    this.chaosGate = chaosGate;
  }

  @KafkaListener(topics = KafkaTopics.SAGA_REPLIES, groupId = "saga-orchestrator")
  public void onMessage(String message) throws Exception {
    chaosGate.maybeCrash(CHAOS_POINT_AFTER_REPLY_BEFORE_STATE_WRITE);

    JsonNode envelope = objectMapper.readTree(message);
    String eventType = envelope.path("eventType").asText(null);
    UUID eventId = UUID.fromString(envelope.path("eventId").asText());
    UUID orderId = UUID.fromString(envelope.path("orderId").asText());
    UUID sagaId =
        envelope.hasNonNull("sagaId") ? UUID.fromString(envelope.get("sagaId").asText()) : null;
    JsonNode payload = envelope.path("payload");

    switch (eventType) {
      case InventoryReservedPayload.EVENT_TYPE -> {
        List<UUID> reservationIds =
            objectMapper.convertValue(
                payload.path("reservationIds"), new TypeReference<List<UUID>>() {});
        List<InventoryItemPayload> items =
            objectMapper.convertValue(
                payload.path("items"), new TypeReference<List<InventoryItemPayload>>() {});
        orchestrationService.handleInventoryReserved(eventId, orderId, reservationIds, items);
      }
      case InventoryReservationFailedPayload.EVENT_TYPE -> {
        String reason = payload.path("reason").asText();
        List<Object> shortfalls =
            objectMapper.convertValue(
                payload.path("shortfalls"), new TypeReference<List<Object>>() {});
        orchestrationService.handleInventoryReservationFailed(eventId, orderId, reason, shortfalls);
      }
      case InventoryReleasedPayload.EVENT_TYPE -> {
        List<UUID> reservationIds =
            objectMapper.convertValue(
                payload.path("reservationIds"), new TypeReference<List<UUID>>() {});
        orchestrationService.handleInventoryReleased(eventId, orderId, reservationIds);
      }
      case PaymentChargedPayload.EVENT_TYPE -> {
        UUID paymentId = UUID.fromString(payload.path("paymentId").asText());
        BigDecimal amount = new BigDecimal(payload.path("amount").asText());
        String gatewayReference = payload.path("gatewayReference").asText();
        orchestrationService.handlePaymentCharged(
            eventId, orderId, paymentId, amount, gatewayReference);
        if (sagaId != null) {
          orchestrationService.tryConfirm(sagaId);
        }
      }
      case PaymentFailedPayload.EVENT_TYPE -> {
        String reason = payload.path("reason").asText();
        orchestrationService.handlePaymentFailed(eventId, orderId, reason);
      }
      case PaymentRefundedPayload.EVENT_TYPE -> {
        UUID paymentId = UUID.fromString(payload.path("paymentId").asText());
        orchestrationService.handlePaymentRefunded(eventId, orderId, paymentId);
      }
      default ->
          log.debug(
              "Ignoring unrecognized reply eventType {} on {}",
              eventType,
              KafkaTopics.SAGA_REPLIES);
    }

    chaosGate.maybeCrash(CHAOS_POINT_AFTER_STATE_WRITE_BEFORE_COMMAND);
  }
}
