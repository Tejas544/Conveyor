package com.conveyor.order.messaging;

import com.conveyor.common.kafka.KafkaTopics;
import com.conveyor.order.domain.IllegalOrderTransitionException;
import com.conveyor.order.domain.Order;
import com.conveyor.order.domain.OrderStatus;
import com.conveyor.order.outbox.InboxRecord;
import com.conveyor.order.outbox.InboxRecordId;
import com.conveyor.order.outbox.InboxRecordRepository;
import com.conveyor.order.repository.OrderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * ARCHITECTURE.md §4, §7.1: projects saga progress onto {@code orders.status}. Until Phase 6 builds
 * saga-orchestrator, nothing publishes {@code InventoryReserved}/{@code PaymentCharged}/{@code
 * OrderConfirmed}/{@code OrderCancelled}/{@code *Failed}, so this listener never fires in normal
 * operation — a harmless no-op, wired and tested now so Phase 6 has somewhere to land. Also
 * consumes {@code conveyor.order.events.v1}, which order-service itself produces {@code
 * OrderPlaced} onto (§6.2): {@link #mapToStatus} returns {@code null} for anything it doesn't
 * recognize, so the service's own events pass through harmlessly rather than needing a special
 * case.
 *
 * <p>Single-writer rule (ARCHITECTURE.md §7): this is the <em>only</em> place order-service writes
 * {@code orders.status} after creation. Inbox-deduplicated like every other consumer (§9).
 */
@Component
public class SagaEventProjectionListener {

  private static final Logger log = LoggerFactory.getLogger(SagaEventProjectionListener.class);
  private static final String CONSUMER_NAME = "order-service-status-projection";

  private final OrderRepository orderRepository;
  private final InboxRecordRepository inboxRecordRepository;
  private final ObjectMapper objectMapper;

  public SagaEventProjectionListener(
      OrderRepository orderRepository,
      InboxRecordRepository inboxRecordRepository,
      ObjectMapper objectMapper) {
    this.orderRepository = orderRepository;
    this.inboxRecordRepository = inboxRecordRepository;
    this.objectMapper = objectMapper;
  }

  @KafkaListener(
      topics = {KafkaTopics.SAGA_REPLIES, KafkaTopics.ORDER_EVENTS},
      groupId = "order-service")
  @Transactional
  public void onMessage(String message) throws Exception {
    JsonNode envelope = objectMapper.readTree(message);
    String eventType = envelope.path("eventType").asText(null);
    OrderStatus target = mapToStatus(eventType);
    if (target == null) {
      return;
    }

    UUID eventId = UUID.fromString(envelope.path("eventId").asText());
    InboxRecordId inboxId = new InboxRecordId(eventId, CONSUMER_NAME);
    if (inboxRecordRepository.existsById(inboxId)) {
      return;
    }

    UUID orderId = UUID.fromString(envelope.path("orderId").asText());
    Order order = orderRepository.findById(orderId).orElse(null);
    if (order == null) {
      log.warn("{} for unknown order {}, ignoring", eventType, orderId);
      inboxRecordRepository.save(new InboxRecord(inboxId));
      return;
    }

    try {
      order.transitionTo(target);
      orderRepository.save(order);
    } catch (IllegalOrderTransitionException e) {
      log.warn(
          "Ignoring {} for order {}: {} (saga and projection disagree on state)",
          eventType,
          orderId,
          e.getMessage());
    }
    inboxRecordRepository.save(new InboxRecord(inboxId));
  }

  private OrderStatus mapToStatus(String eventType) {
    if (eventType == null) {
      return null;
    }
    return switch (eventType) {
      case "InventoryReserved" -> OrderStatus.INVENTORY_RESERVED;
      case "PaymentCharged" -> OrderStatus.PAYMENT_CHARGED;
      case "OrderConfirmed" -> OrderStatus.CONFIRMED;
      case "OrderCancelled" -> OrderStatus.CANCELLED;
      case "InventoryReservationFailed", "PaymentFailed" -> OrderStatus.COMPENSATING;
      default -> null;
    };
  }
}
