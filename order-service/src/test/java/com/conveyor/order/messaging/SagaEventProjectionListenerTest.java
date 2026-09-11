package com.conveyor.order.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.conveyor.common.envelope.ConveyorEnvelope;
import com.conveyor.common.kafka.KafkaTopics;
import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.order.domain.Order;
import com.conveyor.order.domain.OrderItem;
import com.conveyor.order.domain.OrderStatus;
import com.conveyor.order.repository.OrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PLAN.md Phase 3 deliverable: "Consumers projecting saga replies onto orders.status (harmless
 * no-ops until Phase 6 produces those events)." Nothing publishes these in normal operation yet —
 * this test drives the projection directly by publishing a hand-built envelope, standing in for the
 * saga-orchestrator that Phase 6 adds.
 */
class SagaEventProjectionListenerTest extends AbstractIntegrationTest {

  @Autowired private OrderRepository orderRepository;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private TransactionTemplate transactionTemplate;

  @Test
  void inventoryReservedReplyAdvancesOrderStatus() throws Exception {
    UUID orderId = UUID.randomUUID();
    transactionTemplate.executeWithoutResult(
        status -> {
          Order order =
              new Order(
                  orderId,
                  UUID.randomUUID(),
                  OrderStatus.PLACED,
                  new BigDecimal("9.99"),
                  "USD",
                  Map.of(
                      "line1",
                      "1 Test St",
                      "city",
                      "Testville",
                      "postalCode",
                      "00000",
                      "country",
                      "IN"),
                  null);
          order.addItem(new OrderItem(UUID.randomUUID(), "SKU-PROJ-1", 1, new BigDecimal("9.99")));
          orderRepository.save(order);
        });

    ConveyorEnvelope<Map<String, Object>> envelope =
        ConveyorEnvelope.of(
            "InventoryReserved",
            1,
            "inventory-service",
            UUID.randomUUID(),
            orderId,
            null,
            Map.of("reservationIds", java.util.List.of(UUID.randomUUID().toString())));
    String json = objectMapper.writeValueAsString(envelope);

    Properties producerProps = new Properties();
    producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, REDPANDA.getBootstrapServers());
    producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps)) {
      producer.send(new ProducerRecord<>(KafkaTopics.SAGA_REPLIES, orderId.toString(), json)).get();
    }

    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () ->
                assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                    .isEqualTo(OrderStatus.INVENTORY_RESERVED));
  }

  /**
   * BUG-0026: {@code SagaTimeoutSweeper}'s deadline-driven aborts publish {@code OrderCancelled}
   * directly, with no intermediate {@code InventoryReservationFailed}/{@code PaymentFailed} reply
   * to drive this listener through {@code COMPENSATING} first — unlike a reply-triggered
   * compensation. Before the fix, {@code INVENTORY_RESERVED -> CANCELLED} was an illegal
   * transition the listener silently swallowed (logged, not thrown), leaving the order stuck
   * reporting an in-progress status forever even though the saga had already correctly reached
   * {@code ABORTED}. Found live via Phase 11's chaos matrix.
   */
  @Test
  void orderCancelledAdvancesOrderStatusDirectlyFromInventoryReservedWithNoInterveningCompensatingEvent()
      throws Exception {
    UUID orderId = UUID.randomUUID();
    transactionTemplate.executeWithoutResult(
        status -> {
          Order order =
              new Order(
                  orderId,
                  UUID.randomUUID(),
                  OrderStatus.PLACED,
                  new BigDecimal("9.99"),
                  "USD",
                  Map.of(
                      "line1",
                      "1 Test St",
                      "city",
                      "Testville",
                      "postalCode",
                      "00000",
                      "country",
                      "IN"),
                  null);
          order.addItem(new OrderItem(UUID.randomUUID(), "SKU-PROJ-2", 1, new BigDecimal("9.99")));
          order.transitionTo(OrderStatus.INVENTORY_RESERVED);
          orderRepository.save(order);
        });

    ConveyorEnvelope<Map<String, Object>> envelope =
        ConveyorEnvelope.of(
            "OrderCancelled",
            1,
            "saga-orchestrator",
            UUID.randomUUID(),
            orderId,
            null,
            Map.of("reason", "RESERVE_INVENTORY_TIMEOUT", "compensatedSteps", java.util.List.of()));
    String json = objectMapper.writeValueAsString(envelope);

    Properties producerProps = new Properties();
    producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, REDPANDA.getBootstrapServers());
    producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps)) {
      producer.send(new ProducerRecord<>(KafkaTopics.ORDER_EVENTS, orderId.toString(), json)).get();
    }

    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () ->
                assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                    .isEqualTo(OrderStatus.CANCELLED));
  }
}
