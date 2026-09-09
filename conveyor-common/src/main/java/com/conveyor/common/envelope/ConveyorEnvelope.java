package com.conveyor.common.envelope;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/**
 * The envelope every message on every Kafka topic shares (ARCHITECTURE.md §6.1). {@code payload}
 * carries the event- or command-specific fields and is validated separately against that event
 * type's JSON Schema in conveyor-contracts.
 *
 * @param eventId unique per message, UUIDv7 — the inbox dedup key
 * @param eventType e.g. "OrderPlaced", "ReserveInventory"
 * @param schemaVersion ADR-6's compatibility gate operates on this
 * @param occurredAt when the fact became true / the command was issued
 * @param producer the service that produced this message
 * @param sagaId null until a saga exists for this order
 * @param orderId also the Kafka message key
 * @param correlationId equals sagaId for all saga traffic
 * @param causationId eventId of the message that caused this one, if any
 * @param payload event/command-specific fields
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConveyorEnvelope<T>(
    UUID eventId,
    String eventType,
    int schemaVersion,
    Instant occurredAt,
    String producer,
    UUID sagaId,
    UUID orderId,
    UUID correlationId,
    UUID causationId,
    T payload) {

  /**
   * Convenience factory for a saga-scoped message, where correlationId is always the sagaId
   * (ARCHITECTURE.md §6.1).
   */
  public static <T> ConveyorEnvelope<T> of(
      String eventType,
      int schemaVersion,
      String producer,
      UUID sagaId,
      UUID orderId,
      UUID causationId,
      T payload) {
    return new ConveyorEnvelope<>(
        UuidV7.generate(),
        eventType,
        schemaVersion,
        Instant.now(),
        producer,
        sagaId,
        orderId,
        sagaId != null ? sagaId : orderId,
        causationId,
        payload);
  }
}
