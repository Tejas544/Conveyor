package com.conveyor.order.web.dto;

import com.conveyor.order.domain.OrderStatus;
import java.util.UUID;

/** ARCHITECTURE.md §10.1 — {@code sagaId} is null until Phase 6's saga-orchestrator exists. */
public record CreateOrderResponse(UUID orderId, UUID sagaId, OrderStatus status) {}
