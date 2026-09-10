package com.conveyor.order.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ShippingAddressRequest(
    @NotBlank String line1,
    @NotBlank String city,
    @NotBlank String postalCode,
    @NotBlank String country) {}
