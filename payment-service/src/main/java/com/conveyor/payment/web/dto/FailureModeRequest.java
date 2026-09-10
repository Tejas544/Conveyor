package com.conveyor.payment.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** ARCHITECTURE.md §10.4: {@code POST /test/failure-mode} body, {@code chaos} profile only. */
public record FailureModeRequest(
    @NotBlank @Pattern(regexp = "DECLINE|TIMEOUT|ERROR") String mode,
    @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double probability) {}
