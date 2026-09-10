package com.conveyor.order.service;

import com.conveyor.order.domain.Order;

/**
 * {@code created=false} means the call was an {@code Idempotency-Key} replay of an existing order.
 */
public record OrderCreationResult(Order order, boolean created) {}
