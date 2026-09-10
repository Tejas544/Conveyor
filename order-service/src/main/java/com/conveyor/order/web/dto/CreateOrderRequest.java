package com.conveyor.order.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;

/**
 * ARCHITECTURE.md §10.1's example request omits {@code unitPrice} per item and {@code currency} —
 * reasonable for a document sketch, but order-service has no synchronous call to Inventory
 * Service's catalog to resolve a price from (services never call each other synchronously;
 * everything downstream of {@code OrderPlaced} is async, by design). Logged in CONTEXT.md: the
 * client supplies both explicitly rather than the server guessing or reaching across a service
 * boundary it shouldn't have.
 */
public record CreateOrderRequest(
    @NotNull UUID customerId,
    @NotEmpty @Valid List<CreateOrderItemRequest> items,
    @NotNull @Valid ShippingAddressRequest shippingAddress,
    @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
    @NotBlank String paymentMethodToken) {}
