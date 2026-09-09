package com.conveyor.common.envelope;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Phase 1 exit criterion (PLAN.md): an envelope produced by conveyor-common round-trips through
 * JSON and is deserialized identically to the original.
 */
class ConveyorEnvelopeSerializationTest {

  private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

  @Test
  void roundTripsThroughJsonIdentically() throws Exception {
    ConveyorEnvelope<Map<String, Object>> original =
        ConveyorEnvelope.of(
            "OrderPlaced",
            1,
            "order-service",
            null,
            UUID.randomUUID(),
            null,
            Map.of("customerId", UUID.randomUUID().toString(), "totalAmount", 42.50));

    String json = objectMapper.writeValueAsString(original);
    ConveyorEnvelope<Map<String, Object>> roundTripped =
        objectMapper.readValue(json, new EnvelopeMapPayloadTypeRef());

    assertThat(roundTripped).isEqualTo(original);
  }

  @Test
  void sagaCorrelationIdDefaultsToSagaIdWhenPresent() {
    UUID sagaId = UUID.randomUUID();
    ConveyorEnvelope<Void> envelope =
        ConveyorEnvelope.of(
            "PaymentCharged", 1, "payment-service", sagaId, UUID.randomUUID(), null, null);

    assertThat(envelope.correlationId()).isEqualTo(sagaId);
  }

  @Test
  void sagaCorrelationIdFallsBackToOrderIdWhenNoSagaYetExists() {
    UUID orderId = UUID.randomUUID();
    ConveyorEnvelope<Void> envelope =
        ConveyorEnvelope.of("OrderPlaced", 1, "order-service", null, orderId, null, null);

    assertThat(envelope.correlationId()).isEqualTo(orderId);
  }

  @Test
  void eventIdIsTimeOrderedUuidV7() throws InterruptedException {
    UUID a = UuidV7.generate();
    // Two IDs generated within the same millisecond are not guaranteed to
    // order correctly (RFC 9562 §6.2 — sub-millisecond ordering is only
    // "roughly" preserved via the random bits). Sleeping past a millisecond
    // boundary makes the ordering assertion below deterministic rather than
    // flaky.
    Thread.sleep(2);
    UUID b = UuidV7.generate();

    assertThat(a.version()).isEqualTo(7);
    assertThat(b.version()).isEqualTo(7);
    assertThat(a.compareTo(b)).isLessThan(0);
  }

  /** Named subclass instead of an inline anonymous TypeReference for readability. */
  private static final class EnvelopeMapPayloadTypeRef
      extends com.fasterxml.jackson.core.type.TypeReference<
          ConveyorEnvelope<Map<String, Object>>> {}
}
