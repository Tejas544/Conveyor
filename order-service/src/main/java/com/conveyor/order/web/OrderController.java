package com.conveyor.order.web;

import com.conveyor.common.chaos.ChaosGate;
import com.conveyor.order.domain.Order;
import com.conveyor.order.domain.OrderStatus;
import com.conveyor.order.service.OrderCreationResult;
import com.conveyor.order.service.OrderService;
import com.conveyor.order.web.dto.CreateOrderRequest;
import com.conveyor.order.web.dto.CreateOrderResponse;
import com.conveyor.order.web.dto.OrderDetailResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** ARCHITECTURE.md §10.1. */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders")
public class OrderController {

  private static final String CHAOS_POINT_AFTER_COMMIT_BEFORE_PUBLISH =
      "order.after-commit-before-publish";

  private final OrderService orderService;
  private final ChaosGate chaosGate;

  public OrderController(OrderService orderService, ChaosGate chaosGate) {
    this.orderService = orderService;
    this.chaosGate = chaosGate;
  }

  @PostMapping
  @Operation(
      summary = "Place an order",
      description =
          "Public — the customer path. Honours Idempotency-Key: replaying the same key returns "
              + "the original order with 200 instead of creating a second one.")
  public ResponseEntity<CreateOrderResponse> createOrder(
      @Valid @RequestBody CreateOrderRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    OrderCreationResult result = orderService.createOrder(request, idempotencyKey);

    // ARCHITECTURE.md §14: fires only when armed via CONVEYOR_CHAOS_CRASH_AT, and only for a
    // genuinely new order — the transaction above has already committed by the time control
    // returns here, so this is precisely "after commit, before the outbox poller ever ran".
    if (result.created()) {
      chaosGate.maybeCrash(CHAOS_POINT_AFTER_COMMIT_BEFORE_PUBLISH);
    }

    CreateOrderResponse body =
        new CreateOrderResponse(
            result.order().getId(), result.order().getSagaId(), result.order().getStatus());
    return result.created()
        ? ResponseEntity.status(HttpStatus.ACCEPTED).body(body)
        : ResponseEntity.ok(body);
  }

  @GetMapping("/summary")
  @Operation(summary = "Order counts by status — the kanban column headers.")
  public Map<OrderStatus, Long> summary() {
    return orderService.statusSummary();
  }

  @GetMapping("/{orderId}")
  @Operation(summary = "Order detail — order, items, and current status.")
  public OrderDetailResponse getOrder(@PathVariable UUID orderId) {
    return OrderDetailResponse.from(orderService.getOrder(orderId));
  }

  @GetMapping
  @Operation(summary = "Kanban columns — orders filtered by status and/or creation-time range.")
  public Page<OrderDetailResponse> listOrders(
      @RequestParam(required = false) OrderStatus status,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      Pageable pageable) {
    Page<Order> orders = orderService.searchOrders(status, from, to, pageable);
    return orders.map(OrderDetailResponse::from);
  }
}
