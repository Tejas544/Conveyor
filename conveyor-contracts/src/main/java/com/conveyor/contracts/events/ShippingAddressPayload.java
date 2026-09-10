package com.conveyor.contracts.events;

/** ARCHITECTURE.md §5.1 (stored as {@code orders.shipping_address jsonb}), §6.3. */
public record ShippingAddressPayload(
    String line1, String city, String postalCode, String country) {}
