package com.conveyor.payment.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.conveyor.common.envelope.ConveyorEnvelope;
import com.conveyor.common.kafka.KafkaTopics;
import com.conveyor.common.outbox.OutboxPoller;
import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.contracts.events.ChargePaymentPayload;
import com.conveyor.contracts.events.RefundPaymentPayload;
import com.conveyor.contracts.schema.SchemaValidator;
import com.conveyor.payment.domain.Payment;
import com.conveyor.payment.domain.PaymentStatus;
import com.conveyor.payment.gateway.MockPaymentGateway;
import com.conveyor.payment.outbox.OutboxRecordRepository;
import com.conveyor.payment.repository.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.ValidationMessage;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * ADR-6: every producer contract test validates its emitted event against the published JSON
 * Schema. Drives the whole pipeline for real — a command published onto {@code
 * conveyor.payment.commands.v1}, consumed by {@link PaymentCommandListener}, replied to on {@code
 * conveyor.saga.replies.v1} via the outbox — for all three of PLAN.md Phase 5's reply events.
 */
class PaymentReplyContractTest extends AbstractIntegrationTest {

  @Autowired private KafkaTemplate<String, String> kafkaTemplate;
  @Autowired private OutboxPoller outboxPoller;
  @Autowired private OutboxRecordRepository outboxRecordRepository;
  @Autowired private PaymentRepository paymentRepository;
  @Autowired private MockPaymentGateway gateway;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void chargePaymentSucceedsAndPaymentChargedValidatesAgainstItsSchema() throws Exception {
    UUID orderId = UUID.randomUUID();
    publishChargeCommand(orderId, "saga-" + orderId + ":CHARGE_PAYMENT");

    String reply = awaitReply(orderId, "PaymentCharged");
    assertSchemaValid(reply, "envelope.schema.json");
    assertSchemaValid(reply, "payment-charged.schema.json");
  }

  @Test
  void declinedChargeProducesAValidPaymentFailed() throws Exception {
    gateway.armFailureMode("DECLINE", 1.0);
    UUID orderId = UUID.randomUUID();
    publishChargeCommand(orderId, "saga-" + orderId + ":CHARGE_PAYMENT");

    String reply = awaitReply(orderId, "PaymentFailed");
    assertSchemaValid(reply, "envelope.schema.json");
    assertSchemaValid(reply, "payment-failed.schema.json");
    gateway.disarm();
  }

  @Test
  void refundPaymentProducesAValidPaymentRefunded() throws Exception {
    UUID orderId = UUID.randomUUID();
    Payment payment =
        paymentRepository.saveAndFlush(
            new Payment(
                UUID.randomUUID(),
                orderId,
                new BigDecimal("15.00"),
                "USD",
                PaymentStatus.CAPTURED,
                "gw_contract_test"));

    ConveyorEnvelope<RefundPaymentPayload> envelope =
        ConveyorEnvelope.of(
            RefundPaymentPayload.EVENT_TYPE,
            RefundPaymentPayload.SCHEMA_VERSION,
            "saga-orchestrator",
            null,
            orderId,
            null,
            new RefundPaymentPayload(
                payment.getId(), payment.getAmount(), "saga-" + orderId + ":REFUND_PAYMENT"));
    kafkaTemplate
        .send(
            KafkaTopics.PAYMENT_COMMANDS,
            orderId.toString(),
            objectMapper.writeValueAsString(envelope))
        .get();

    String reply = awaitReply(orderId, "PaymentRefunded");
    assertSchemaValid(reply, "envelope.schema.json");
    assertSchemaValid(reply, "payment-refunded.schema.json");
  }

  private void publishChargeCommand(UUID orderId, String idempotencyKey) throws Exception {
    ConveyorEnvelope<ChargePaymentPayload> envelope =
        ConveyorEnvelope.of(
            ChargePaymentPayload.EVENT_TYPE,
            ChargePaymentPayload.SCHEMA_VERSION,
            "saga-orchestrator",
            null,
            orderId,
            null,
            new ChargePaymentPayload(
                new BigDecimal("25.00"), "USD", "tok_test_visa", idempotencyKey));
    kafkaTemplate
        .send(
            KafkaTopics.PAYMENT_COMMANDS,
            orderId.toString(),
            objectMapper.writeValueAsString(envelope))
        .get();
  }

  private String awaitReply(UUID orderId, String expectedEventType) throws Exception {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      boolean present =
          outboxRecordRepository.findAll().stream()
              .anyMatch(
                  r ->
                      r.getAggregateId().equals(orderId.toString())
                          && r.getEventType().equals(expectedEventType));
      if (present) {
        break;
      }
      Thread.sleep(100);
    }

    outboxPoller.publishOneBatch();

    try (Consumer<String, String> consumer =
        com.conveyor.payment.testsupport.TestKafkaConsumers.subscribedTo(
            REDPANDA.getBootstrapServers(), KafkaTopics.SAGA_REPLIES)) {
      var records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10));
      for (var record : records) {
        if (record.key().equals(orderId.toString())) {
          return record.value();
        }
      }
      throw new AssertionError("No reply observed for order " + orderId);
    }
  }

  private void assertSchemaValid(String json, String schemaFileName) {
    Set<ValidationMessage> errors = SchemaValidator.validate(schemaFileName, json);
    assertThat(errors).as("%s errors: %s", schemaFileName, errors).isEmpty();
  }
}
