package com.conveyor.inventory.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** ARCHITECTURE.md §10.3: {@code POST /inventory/{sku}/adjust} body. */
public record AdjustStockRequest(@NotNull Integer delta, @NotBlank String reason) {}
