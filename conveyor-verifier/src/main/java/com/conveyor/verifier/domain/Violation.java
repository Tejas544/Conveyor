package com.conveyor.verifier.domain;

/**
 * One offending record. {@code offendingId} is whatever identifies the bad row to a human (an order
 * id, a SKU, a saga id) — never a raw row count, per ARCHITECTURE.md §13's requirement that a
 * violation report names the offending IDs.
 */
public record Violation(InvariantId invariantId, String offendingId, String detail) {}
