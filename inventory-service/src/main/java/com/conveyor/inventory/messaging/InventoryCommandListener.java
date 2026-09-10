package com.conveyor.inventory.messaging;

import com.conveyor.common.chaos.ChaosGate;
import com.conveyor.common.kafka.KafkaTopics;
import com.conveyor.contracts.events.InventoryItemPayload;
import com.conveyor.contracts.events.ReleaseInventoryPayload;
import com.conveyor.contracts.events.ReserveInventoryPayload;
import com.conveyor.inventory.service.InventoryReservationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * ARCHITECTURE.md §6.2, §7.3: consumes {@code ReserveInventory}/{@code ReleaseInventory} commands
 * from {@code conveyor.inventory.commands.v1}. Until Phase 6 builds saga-orchestrator, tests
 * publish these commands directly and assert the reply on {@code conveyor.saga.replies.v1} — the
 * same "exercised by driving it directly" approach order-service's Phase 3 saga-reply listener used
 * (PLAN.md: a phase whose collaborator doesn't exist yet is still exercised end-to-end).
 */
@Component
public class InventoryCommandListener {

  private static final Logger log = LoggerFactory.getLogger(InventoryCommandListener.class);
  private static final String CHAOS_POINT_AFTER_RESERVE_BEFORE_PUBLISH =
      "inventory.after-reserve-before-publish";

  private final InventoryReservationService reservationService;
  private final ObjectMapper objectMapper;
  private final ChaosGate chaosGate;

  public InventoryCommandListener(
      InventoryReservationService reservationService,
      ObjectMapper objectMapper,
      ChaosGate chaosGate) {
    this.reservationService = reservationService;
    this.objectMapper = objectMapper;
    this.chaosGate = chaosGate;
  }

  @KafkaListener(topics = KafkaTopics.INVENTORY_COMMANDS, groupId = "inventory-service")
  public void onMessage(String message) throws Exception {
    JsonNode envelope = objectMapper.readTree(message);
    String eventType = envelope.path("eventType").asText(null);
    UUID eventId = UUID.fromString(envelope.path("eventId").asText());
    UUID orderId = UUID.fromString(envelope.path("orderId").asText());
    UUID sagaId =
        envelope.hasNonNull("sagaId") ? UUID.fromString(envelope.get("sagaId").asText()) : null;
    UUID causationId =
        envelope.hasNonNull("causationId")
            ? UUID.fromString(envelope.get("causationId").asText())
            : null;
    JsonNode payload = envelope.path("payload");

    if (ReserveInventoryPayload.EVENT_TYPE.equals(eventType)) {
      List<InventoryItemPayload> items =
          objectMapper.convertValue(
              payload.path("items"), new TypeReference<List<InventoryItemPayload>>() {});
      reservationService.handleReserveInventory(eventId, orderId, sagaId, causationId, items);
      chaosGate.maybeCrash(CHAOS_POINT_AFTER_RESERVE_BEFORE_PUBLISH);
    } else if (ReleaseInventoryPayload.EVENT_TYPE.equals(eventType)) {
      List<UUID> reservationIds =
          objectMapper.convertValue(
              payload.path("reservationIds"), new TypeReference<List<UUID>>() {});
      reservationService.handleReleaseInventory(
          eventId, orderId, sagaId, causationId, reservationIds);
    } else {
      log.debug(
          "Ignoring unrecognized command eventType {} on {}",
          eventType,
          KafkaTopics.INVENTORY_COMMANDS);
    }
  }
}
