package com.conveyor.order.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record CreateOrderItemRequest(
    @NotBlank String sku,
    @Min(1) int quantity,
    @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal unitPrice) {}
