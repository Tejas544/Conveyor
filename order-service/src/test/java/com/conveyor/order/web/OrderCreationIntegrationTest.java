package com.conveyor.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.conveyor.common.kafka.KafkaTopics;
import com.conveyor.common.outbox.OutboxPoller;
import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.contracts.schema.SchemaValidator;
import com.conveyor.order.domain.Order;
import com.conveyor.order.repository.OrderRepository;
import com.conveyor.order.testsupport.TestKafkaConsumers;
import com.conveyor.order.web.dto.CreateOrderItemRequest;
import com.conveyor.order.web.dto.CreateOrderRequest;
import com.conveyor.order.web.dto.CreateOrderResponse;
import com.conveyor.order.web.dto.ShippingAddressRequest;
import com.networknt.schema.ValidationMessage;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * PLAN.md Phase 3: {@code POST /orders} → row in {@code orders} and {@code OrderPlaced} observed on
 * {@code conveyor.order.events.v1} with the correct envelope, plus the contract test proving that
 * envelope validates against its published JSON Schema (ADR-6).
 */
class OrderCreationIntegrationTest extends AbstractIntegrationTest {

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private OrderRepository orderRepository;
  @Autowired private OutboxPoller outboxPoller;

  private CreateOrderRequest sampleRequest() {
    return new CreateOrderRequest(
        UUID.randomUUID(),
        List.of(new CreateOrderItemRequest("SKU-1042", 2, new BigDecimal("9.99"))),
        new ShippingAddressRequest("1 Test St", "Testville", "00000", "IN"),
        "USD",
        "tok_test_visa");
  }

  @Test
  void placingAnOrderPersistsItAndPublishesOrderPlacedWithAValidEnvelope() throws Exception {
    ResponseEntity<CreateOrderResponse> response =
        restTemplate.postForEntity("/api/v1/orders", sampleRequest(), CreateOrderResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    UUID orderId = response.getBody().orderId();

    Order persisted = orderRepository.findById(orderId).orElseThrow();
    assertThat(persisted.getTotalAmount()).isEqualByComparingTo("19.98");

    outboxPoller.publishOneBatch();

    try (Consumer<String, String> consumer =
        TestKafkaConsumers.subscribedTo(REDPANDA.getBootstrapServers(), KafkaTopics.ORDER_EVENTS)) {
      ConsumerRecord<String, String> record =
          KafkaTestUtils.getSingleRecord(
              consumer, KafkaTopics.ORDER_EVENTS, Duration.ofSeconds(10));

      assertThat(record.key()).isEqualTo(orderId.toString());

      Set<ValidationMessage> envelopeErrors =
          SchemaValidator.validate("envelope.schema.json", record.value());
      assertThat(envelopeErrors).as("envelope schema: %s", envelopeErrors).isEmpty();

      Set<ValidationMessage> orderPlacedErrors =
          SchemaValidator.validate("order-placed.schema.json", record.value());
      assertThat(orderPlacedErrors).as("order-placed schema: %s", orderPlacedErrors).isEmpty();

      com.fasterxml.jackson.databind.JsonNode envelope =
          new com.fasterxml.jackson.databind.ObjectMapper().readTree(record.value());
      assertThat(envelope.get("orderId").asText()).isEqualTo(orderId.toString());
      assertThat(envelope.get("eventType").asText()).isEqualTo("OrderPlaced");
    }
  }
}
