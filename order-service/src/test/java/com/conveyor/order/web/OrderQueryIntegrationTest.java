package com.conveyor.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.order.domain.OrderStatus;
import com.conveyor.order.web.dto.CreateOrderItemRequest;
import com.conveyor.order.web.dto.CreateOrderRequest;
import com.conveyor.order.web.dto.CreateOrderResponse;
import com.conveyor.order.web.dto.OrderDetailResponse;
import com.conveyor.order.web.dto.ShippingAddressRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

class OrderQueryIntegrationTest extends AbstractIntegrationTest {

  @Autowired private TestRestTemplate restTemplate;

  private UUID placeAnOrder() {
    CreateOrderRequest request =
        new CreateOrderRequest(
            UUID.randomUUID(),
            List.of(new CreateOrderItemRequest("SKU-QUERY-1", 3, new BigDecimal("4.00"))),
            new ShippingAddressRequest("1 Test St", "Testville", "00000", "IN"),
            "USD",
            "tok_test_visa");
    return restTemplate
        .postForEntity("/api/v1/orders", request, CreateOrderResponse.class)
        .getBody()
        .orderId();
  }

  @Test
  void getOrderReturnsFullDetail() {
    UUID orderId = placeAnOrder();

    ResponseEntity<OrderDetailResponse> response =
        restTemplate.getForEntity("/api/v1/orders/" + orderId, OrderDetailResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    OrderDetailResponse body = response.getBody();
    assertThat(body.orderId()).isEqualTo(orderId);
    assertThat(body.status()).isEqualTo(OrderStatus.PLACED);
    assertThat(body.totalAmount()).isEqualByComparingTo("12.00");
    assertThat(body.items()).hasSize(1);
  }

  @Test
  void getUnknownOrderReturns404WithProblemDetail() {
    ResponseEntity<ProblemDetail> response =
        restTemplate.getForEntity("/api/v1/orders/" + UUID.randomUUID(), ProblemDetail.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void summaryCountsThePlacedOrder() {
    placeAnOrder();

    ResponseEntity<Map<String, Long>> response =
        restTemplate.exchange(
            "/api/v1/orders/summary",
            org.springframework.http.HttpMethod.GET,
            null,
            new org.springframework.core.ParameterizedTypeReference<Map<String, Long>>() {});

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().get("PLACED")).isGreaterThanOrEqualTo(1L);
  }

  @Test
  void listOrdersFiltersByStatus() {
    placeAnOrder();

    ResponseEntity<Map<String, Object>> response =
        restTemplate.exchange(
            "/api/v1/orders?status=PLACED",
            org.springframework.http.HttpMethod.GET,
            null,
            new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    List<Object> content = (List<Object>) response.getBody().get("content");
    assertThat(content).isNotEmpty();
  }
}
