package com.conveyor.payment.messaging;

import com.conveyor.common.chaos.ChaosGate;
import com.conveyor.common.kafka.KafkaTopics;
import com.conveyor.contracts.events.ChargePaymentPayload;
import com.conveyor.contracts.events.RefundPaymentPayload;
import com.conveyor.payment.service.PaymentChargeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * ARCHITECTURE.md §6.2, §14: consumes {@code ChargePayment}/{@code RefundPayment} from {@code
 * conveyor.payment.commands.v1}. Until Phase 6 builds saga-orchestrator, tests publish these
 * commands directly and assert the reply on {@code conveyor.saga.replies.v1}.
 *
 * <p><strong>Why one retry is always enough</strong> for {@link DataIntegrityViolationException} on
 * {@code payment_attempts.idempotency_key}: that constraint can only ever be lost to exactly one
 * other transaction, ever, for a given key — attempts are never deleted or updated, so once
 * <em>any</em> transaction commits the row for a key, every other caller for that same key, on any
 * subsequent attempt, takes the "existing attempt" branch (a read, not a write) and can never
 * conflict again. A losing transaction's failed insert is rolled back by Postgres already; retrying
 * is just calling the same idempotent method again.
 */
@Component
public class PaymentCommandListener {

  private static final Logger log = LoggerFactory.getLogger(PaymentCommandListener.class);
  private static final String CHAOS_POINT_AFTER_COMMIT_BEFORE_PUBLISH =
      "payment.after-commit-before-publish";

  private final PaymentChargeService paymentChargeService;
  private final ObjectMapper objectMapper;
  private final ChaosGate chaosGate;

  public PaymentCommandListener(
      PaymentChargeService paymentChargeService, ObjectMapper objectMapper, ChaosGate chaosGate) {
    this.paymentChargeService = paymentChargeService;
    this.objectMapper = objectMapper;
    this.chaosGate = chaosGate;
  }

  @KafkaListener(topics = KafkaTopics.PAYMENT_COMMANDS, groupId = "payment-service")
  public void onMessage(String message) throws Exception {
    JsonNode envelope = objectMapper.readTree(message);
    String eventType = envelope.path("eventType").asText(null);
    UUID eventId = UUID.fromString(envelope.path("eventId").asText());
    UUID orderId = UUID.fromString(envelope.path("orderId").asText());
    UUID sagaId =
        envelope.hasNonNull("sagaId") ? UUID.fromString(envelope.get("sagaId").asText()) : null;
    UUID causationId =
        envelope.hasNonNull("causationId")
            ? UUID.fromString(envelope.get("causationId").asText())
            : null;
    JsonNode payload = envelope.path("payload");

    if (ChargePaymentPayload.EVENT_TYPE.equals(eventType)) {
      BigDecimal amount = new BigDecimal(payload.path("amount").asText());
      String currency = payload.path("currency").asText();
      String idempotencyKey = payload.path("idempotencyKey").asText();
      chargeWithRetryOnRace(
          eventId, orderId, sagaId, causationId, amount, currency, idempotencyKey);
      chaosGate.maybeCrash(CHAOS_POINT_AFTER_COMMIT_BEFORE_PUBLISH);
    } else if (RefundPaymentPayload.EVENT_TYPE.equals(eventType)) {
      UUID paymentId = UUID.fromString(payload.path("paymentId").asText());
      BigDecimal amount = new BigDecimal(payload.path("amount").asText());
      String idempotencyKey = payload.path("idempotencyKey").asText();
      paymentChargeService.handleRefundPayment(
          eventId, orderId, sagaId, causationId, paymentId, amount, idempotencyKey);
    } else {
      log.debug(
          "Ignoring unrecognized command eventType {} on {}",
          eventType,
          KafkaTopics.PAYMENT_COMMANDS);
    }
  }

  private void chargeWithRetryOnRace(
      UUID eventId,
      UUID orderId,
      UUID sagaId,
      UUID causationId,
      BigDecimal amount,
      String currency,
      String idempotencyKey) {
    try {
      paymentChargeService.handleChargePayment(
          eventId, orderId, sagaId, causationId, amount, currency, idempotencyKey);
    } catch (DataIntegrityViolationException e) {
      log.debug(
          "Lost the idempotency-key race for {}; retrying to reply from the winner's row",
          idempotencyKey);
      paymentChargeService.handleChargePayment(
          eventId, orderId, sagaId, causationId, amount, currency, idempotencyKey);
    }
  }
}
