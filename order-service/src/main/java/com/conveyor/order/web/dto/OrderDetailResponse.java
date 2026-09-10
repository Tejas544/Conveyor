package com.conveyor.order.web.dto;

import com.conveyor.order.domain.Order;
import com.conveyor.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record OrderDetailResponse(
    UUID orderId,
    UUID customerId,
    OrderStatus status,
    UUID sagaId,
    BigDecimal totalAmount,
    String currency,
    Map<String, Object> shippingAddress,
    List<OrderItemResponse> items,
    Instant createdAt,
    Instant updatedAt) {

  public static OrderDetailResponse from(Order order) {
    return new OrderDetailResponse(
        order.getId(),
        order.getCustomerId(),
        order.getStatus(),
        order.getSagaId(),
        order.getTotalAmount(),
        order.getCurrency(),
        order.getShippingAddress(),
        order.getItems().stream()
            .map(
                item ->
                    new OrderItemResponse(item.getSku(), item.getQuantity(), item.getUnitPrice()))
            .toList(),
        order.getCreatedAt(),
        order.getUpdatedAt());
  }
}
