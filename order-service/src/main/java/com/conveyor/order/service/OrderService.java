package com.conveyor.order.service;

import com.conveyor.common.envelope.ConveyorEnvelope;
import com.conveyor.common.kafka.KafkaTopics;
import com.conveyor.contracts.events.OrderItemPayload;
import com.conveyor.contracts.events.OrderPlacedPayload;
import com.conveyor.contracts.events.ShippingAddressPayload;
import com.conveyor.order.domain.Order;
import com.conveyor.order.domain.OrderItem;
import com.conveyor.order.domain.OrderNotFoundException;
import com.conveyor.order.domain.OrderStatus;
import com.conveyor.order.outbox.OutboxRecord;
import com.conveyor.order.outbox.OutboxRecordRepository;
import com.conveyor.order.repository.OrderRepository;
import com.conveyor.order.web.dto.CreateOrderItemRequest;
import com.conveyor.order.web.dto.CreateOrderRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ARCHITECTURE.md §8.1's first step: {@code BEGIN tx / INSERT orders / INSERT outbox / COMMIT}. The
 * outbox row's {@code payload} is the fully-formed {@link ConveyorEnvelope} JSON — {@link
 * com.conveyor.common.outbox.OutboxPoller} publishes it verbatim, so this is the only place that
 * ever constructs one.
 */
@Service
public class OrderService {

  private static final String PRODUCER = "order-service";

  private final OrderRepository orderRepository;
  private final OutboxRecordRepository outboxRecordRepository;
  private final ObjectMapper objectMapper;

  public OrderService(
      OrderRepository orderRepository,
      OutboxRecordRepository outboxRecordRepository,
      ObjectMapper objectMapper) {
    this.orderRepository = orderRepository;
    this.outboxRecordRepository = outboxRecordRepository;
    this.objectMapper = objectMapper;
  }

  /**
   * {@code idempotencyKey} may be null (no header sent). A present key that matches an existing
   * order returns that order unchanged ({@code created=false}) instead of writing anything — {@code
   * POST /orders} called twice with the same key produces exactly one order.
   */
  @Transactional
  public OrderCreationResult createOrder(CreateOrderRequest request, String idempotencyKey) {
    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
      var existing = orderRepository.findByIdempotencyKey(idempotencyKey);
      if (existing.isPresent()) {
        return new OrderCreationResult(existing.get(), false);
      }
    }

    BigDecimal totalAmount =
        request.items().stream()
            .map(item -> item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    Map<String, Object> shippingAddress = new LinkedHashMap<>();
    shippingAddress.put("line1", request.shippingAddress().line1());
    shippingAddress.put("city", request.shippingAddress().city());
    shippingAddress.put("postalCode", request.shippingAddress().postalCode());
    shippingAddress.put("country", request.shippingAddress().country());

    Order order =
        new Order(
            UUID.randomUUID(),
            request.customerId(),
            OrderStatus.PLACED,
            totalAmount,
            request.currency(),
            shippingAddress,
            idempotencyKey);
    for (CreateOrderItemRequest item : request.items()) {
      order.addItem(
          new OrderItem(UUID.randomUUID(), item.sku(), item.quantity(), item.unitPrice()));
    }
    orderRepository.save(order);

    outboxRecordRepository.save(buildOrderPlacedOutboxRecord(order, request));

    return new OrderCreationResult(order, true);
  }

  @Transactional(readOnly = true)
  public Order getOrder(UUID orderId) {
    return orderRepository.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
  }

  @Transactional(readOnly = true)
  public Page<Order> searchOrders(OrderStatus status, Instant from, Instant to, Pageable pageable) {
    return orderRepository.search(status, from, to, pageable);
  }

  @Transactional(readOnly = true)
  public Map<OrderStatus, Long> statusSummary() {
    Map<OrderStatus, Long> summary = new LinkedHashMap<>();
    for (OrderStatus status : OrderStatus.values()) {
      summary.put(status, 0L);
    }
    for (Object[] row : orderRepository.countByStatusGrouped()) {
      summary.put((OrderStatus) row[0], (Long) row[1]);
    }
    return summary;
  }

  private OutboxRecord buildOrderPlacedOutboxRecord(Order order, CreateOrderRequest request) {
    List<OrderItemPayload> itemPayloads =
        request.items().stream()
            .map(item -> new OrderItemPayload(item.sku(), item.quantity(), item.unitPrice()))
            .toList();
    OrderPlacedPayload payload =
        new OrderPlacedPayload(
            request.customerId(),
            itemPayloads,
            order.getTotalAmount(),
            order.getCurrency(),
            new ShippingAddressPayload(
                request.shippingAddress().line1(),
                request.shippingAddress().city(),
                request.shippingAddress().postalCode(),
                request.shippingAddress().country()));

    ConveyorEnvelope<OrderPlacedPayload> envelope =
        ConveyorEnvelope.of(
            OrderPlacedPayload.EVENT_TYPE,
            OrderPlacedPayload.SCHEMA_VERSION,
            PRODUCER,
            null,
            order.getId(),
            null,
            payload);

    @SuppressWarnings("unchecked")
    Map<String, Object> envelopeMap = objectMapper.convertValue(envelope, Map.class);

    Map<String, Object> headers =
        Map.of(
            "event-type",
            OrderPlacedPayload.EVENT_TYPE,
            "schema-version",
            String.valueOf(OrderPlacedPayload.SCHEMA_VERSION),
            "content-type",
            "application/json");

    return new OutboxRecord(
        UUID.randomUUID(),
        "Order",
        order.getId().toString(),
        OrderPlacedPayload.EVENT_TYPE,
        KafkaTopics.ORDER_EVENTS,
        order.getId().toString(),
        envelopeMap,
        headers);
  }
}
