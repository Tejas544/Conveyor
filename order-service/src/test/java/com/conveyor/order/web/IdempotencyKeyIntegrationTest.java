package com.conveyor.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.order.repository.OrderRepository;
import com.conveyor.order.web.dto.CreateOrderItemRequest;
import com.conveyor.order.web.dto.CreateOrderRequest;
import com.conveyor.order.web.dto.CreateOrderResponse;
import com.conveyor.order.web.dto.ShippingAddressRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * PLAN.md Phase 3: the same {@code Idempotency-Key} twice → one order, 200 with the original body.
 */
class IdempotencyKeyIntegrationTest extends AbstractIntegrationTest {

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private OrderRepository orderRepository;

  @Test
  void replayingTheSameIdempotencyKeyReturnsTheOriginalOrderInstead() {
    CreateOrderRequest request =
        new CreateOrderRequest(
            UUID.randomUUID(),
            List.of(new CreateOrderItemRequest("SKU-IDEM-1", 1, new BigDecimal("12.50"))),
            new ShippingAddressRequest("1 Test St", "Testville", "00000", "IN"),
            "USD",
            "tok_test_visa");

    HttpHeaders headers = new HttpHeaders();
    headers.set("Idempotency-Key", "idem-key-" + UUID.randomUUID());
    HttpEntity<CreateOrderRequest> entity = new HttpEntity<>(request, headers);

    ResponseEntity<CreateOrderResponse> first =
        restTemplate.postForEntity("/api/v1/orders", entity, CreateOrderResponse.class);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    UUID firstOrderId = first.getBody().orderId();

    ResponseEntity<CreateOrderResponse> second =
        restTemplate.postForEntity("/api/v1/orders", entity, CreateOrderResponse.class);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(second.getBody().orderId()).isEqualTo(firstOrderId);

    assertThat(orderRepository.count()).isEqualTo(1);
  }
}
