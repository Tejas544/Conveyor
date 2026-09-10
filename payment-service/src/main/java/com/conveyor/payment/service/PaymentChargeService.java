package com.conveyor.payment.service;

import com.conveyor.common.chaos.ChaosGate;
import com.conveyor.common.envelope.ConveyorEnvelope;
import com.conveyor.common.kafka.KafkaTopics;
import com.conveyor.common.tracing.TraceparentSupport;
import com.conveyor.contracts.events.PaymentChargedPayload;
import com.conveyor.contracts.events.PaymentFailedPayload;
import com.conveyor.contracts.events.PaymentRefundedPayload;
import com.conveyor.payment.domain.Payment;
import com.conveyor.payment.domain.PaymentAttempt;
import com.conveyor.payment.domain.PaymentNotFoundException;
import com.conveyor.payment.domain.PaymentStatus;
import com.conveyor.payment.gateway.GatewayOutcome;
import com.conveyor.payment.gateway.MockPaymentGateway;
import com.conveyor.payment.outbox.InboxRecord;
import com.conveyor.payment.outbox.InboxRecordId;
import com.conveyor.payment.outbox.InboxRecordRepository;
import com.conveyor.payment.outbox.OutboxRecord;
import com.conveyor.payment.outbox.OutboxRecordRepository;
import com.conveyor.payment.repository.PaymentAttemptRepository;
import com.conveyor.payment.repository.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ARCHITECTURE.md §5.4, §9, §14: handles {@code ChargePayment}/{@code RefundPayment} commands.
 * Idempotency is keyed on the command's own {@code idempotencyKey} (not the envelope's {@code
 * eventId} — ADR-5's mock gateway is only ever called once per key, ever): a {@code
 * payment_attempts} row is the durable claim on that key, and a unique-constraint race on it
 * (concurrent redelivery) is the one thing {@link #handleChargePayment} deliberately lets escape as
 * a {@link org.springframework.dao.DataIntegrityViolationException} — {@link
 * com.conveyor.payment.messaging.PaymentCommandListener} retries once, which is always enough (see
 * that class's Javadoc for why exactly one retry suffices).
 */
@Service
public class PaymentChargeService {

  static final String CONSUMER_NAME = "payment-service";
  private static final String PRODUCER = "payment-service";
  private static final String CHAOS_POINT_BEFORE_COMMIT = "payment.before-commit";
  private static final Logger log = LoggerFactory.getLogger(PaymentChargeService.class);

  private final PaymentRepository paymentRepository;
  private final PaymentAttemptRepository paymentAttemptRepository;
  private final InboxRecordRepository inboxRecordRepository;
  private final OutboxRecordRepository outboxRecordRepository;
  private final MockPaymentGateway gateway;
  private final ChaosGate chaosGate;
  private final ObjectMapper objectMapper;
  private final TraceparentSupport traceparentSupport;
  private final Counter inboxDuplicatesCounter;

  public PaymentChargeService(
      PaymentRepository paymentRepository,
      PaymentAttemptRepository paymentAttemptRepository,
      InboxRecordRepository inboxRecordRepository,
      OutboxRecordRepository outboxRecordRepository,
      MockPaymentGateway gateway,
      ChaosGate chaosGate,
      ObjectMapper objectMapper,
      TraceparentSupport traceparentSupport,
      MeterRegistry meterRegistry) {
    this.paymentRepository = paymentRepository;
    this.paymentAttemptRepository = paymentAttemptRepository;
    this.inboxRecordRepository = inboxRecordRepository;
    this.outboxRecordRepository = outboxRecordRepository;
    this.gateway = gateway;
    this.chaosGate = chaosGate;
    this.objectMapper = objectMapper;
    this.traceparentSupport = traceparentSupport;
    this.inboxDuplicatesCounter =
        Counter.builder("conveyor_inbox_duplicates_total")
            .tag("consumer", CONSUMER_NAME)
            .register(meterRegistry);
  }

  @Transactional
  public void handleChargePayment(
      UUID eventId,
      UUID orderId,
      UUID sagaId,
      UUID causationId,
      BigDecimal amount,
      String currency,
      String idempotencyKey) {
    InboxRecordId inboxId = new InboxRecordId(eventId, CONSUMER_NAME);
    boolean duplicate = inboxRecordRepository.existsById(inboxId);
    if (duplicate) {
      inboxDuplicatesCounter.increment();
    }

    Optional<PaymentAttempt> existing =
        paymentAttemptRepository.findByIdempotencyKey(idempotencyKey);
    OutboxRecord reply =
        existing.isPresent()
            ? chargeReplyFromAttempt(orderId, sagaId, causationId, existing.get())
            : attemptCharge(orderId, sagaId, causationId, amount, currency, idempotencyKey);

    if (!duplicate) {
      inboxRecordRepository.save(new InboxRecord(inboxId));
    }
    outboxRecordRepository.save(reply);
    log.info("{} for order {}", reply.getEventType(), orderId);
  }

  private OutboxRecord attemptCharge(
      UUID orderId,
      UUID sagaId,
      UUID causationId,
      BigDecimal amount,
      String currency,
      String idempotencyKey) {
    // Nothing has been written yet — a crash here means "the charge never happened," the honest
    // case a saga step timeout is built to handle (ARCHITECTURE.md §14).
    chaosGate.maybeCrash(CHAOS_POINT_BEFORE_COMMIT);

    GatewayOutcome outcome = gateway.charge();

    if (outcome instanceof GatewayOutcome.Captured captured) {
      Payment payment =
          new Payment(
              UUID.randomUUID(),
              orderId,
              amount,
              currency,
              PaymentStatus.CAPTURED,
              captured.gatewayReference());
      paymentRepository.save(payment);
      paymentAttemptRepository.saveAndFlush(
          new PaymentAttempt(
              UUID.randomUUID(), orderId, idempotencyKey, "CAPTURED", captured.gatewayReference()));
      return buildChargedReply(orderId, sagaId, causationId, payment);
    }

    String reason = reasonFor(outcome);
    paymentAttemptRepository.saveAndFlush(
        new PaymentAttempt(UUID.randomUUID(), orderId, idempotencyKey, reason, null));
    return buildFailedReply(orderId, sagaId, causationId, reason);
  }

  private OutboxRecord chargeReplyFromAttempt(
      UUID orderId, UUID sagaId, UUID causationId, PaymentAttempt attempt) {
    if ("CAPTURED".equals(attempt.getOutcome())) {
      Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
      return buildChargedReply(orderId, sagaId, causationId, payment);
    }
    return buildFailedReply(orderId, sagaId, causationId, attempt.getOutcome());
  }

  @Transactional
  public void handleRefundPayment(
      UUID eventId,
      UUID orderId,
      UUID sagaId,
      UUID causationId,
      UUID paymentId,
      BigDecimal amount,
      String idempotencyKey) {
    InboxRecordId inboxId = new InboxRecordId(eventId, CONSUMER_NAME);
    boolean duplicate = inboxRecordRepository.existsById(inboxId);
    if (duplicate) {
      inboxDuplicatesCounter.increment();
    }

    // PLAN.md Phase 5: refunding a payment that doesn't exist fails loudly — this lookup throws
    // (nothing gets written, inbox included) instead of silently doing nothing, on every delivery,
    // idempotent replay or not.
    Payment payment =
        paymentRepository
            .findById(paymentId)
            .orElseThrow(() -> PaymentNotFoundException.forPaymentId(paymentId));

    Optional<PaymentAttempt> existing =
        paymentAttemptRepository.findByIdempotencyKey(idempotencyKey);
    OutboxRecord reply;
    if (existing.isPresent()) {
      reply = buildRefundedReply(orderId, sagaId, causationId, payment, existing.get());
    } else {
      payment.setStatus(PaymentStatus.REFUNDED);
      paymentRepository.save(payment);
      PaymentAttempt attempt =
          new PaymentAttempt(
              UUID.randomUUID(),
              orderId,
              idempotencyKey,
              "REFUNDED",
              payment.getGatewayReference());
      paymentAttemptRepository.saveAndFlush(attempt);
      reply = buildRefundedReply(orderId, sagaId, causationId, payment, attempt);
    }

    if (!duplicate) {
      inboxRecordRepository.save(new InboxRecord(inboxId));
    }
    outboxRecordRepository.save(reply);
  }

  private String reasonFor(GatewayOutcome outcome) {
    if (outcome instanceof GatewayOutcome.Declined) {
      return PaymentFailedPayload.REASON_DECLINED;
    }
    if (outcome instanceof GatewayOutcome.TimedOut) {
      return PaymentFailedPayload.REASON_TIMEOUT;
    }
    return PaymentFailedPayload.REASON_GATEWAY_ERROR;
  }

  private boolean retryableFor(String reason) {
    return !PaymentFailedPayload.REASON_DECLINED.equals(reason);
  }

  private OutboxRecord buildChargedReply(
      UUID orderId, UUID sagaId, UUID causationId, Payment payment) {
    PaymentChargedPayload payload =
        new PaymentChargedPayload(
            payment.getId(),
            payment.getAmount(),
            payment.getCurrency(),
            payment.getGatewayReference());
    return buildOutboxRecord(
        PaymentChargedPayload.EVENT_TYPE,
        PaymentChargedPayload.SCHEMA_VERSION,
        payload,
        orderId,
        sagaId,
        causationId);
  }

  private OutboxRecord buildFailedReply(
      UUID orderId, UUID sagaId, UUID causationId, String reason) {
    PaymentFailedPayload payload = new PaymentFailedPayload(reason, retryableFor(reason));
    return buildOutboxRecord(
        PaymentFailedPayload.EVENT_TYPE,
        PaymentFailedPayload.SCHEMA_VERSION,
        payload,
        orderId,
        sagaId,
        causationId);
  }

  private OutboxRecord buildRefundedReply(
      UUID orderId, UUID sagaId, UUID causationId, Payment payment, PaymentAttempt attempt) {
    BigDecimal amount = payment.getAmount();
    PaymentRefundedPayload payload =
        new PaymentRefundedPayload(payment.getId(), amount, attempt.getGatewayReference());
    return buildOutboxRecord(
        PaymentRefundedPayload.EVENT_TYPE,
        PaymentRefundedPayload.SCHEMA_VERSION,
        payload,
        orderId,
        sagaId,
        causationId);
  }

  private <T> OutboxRecord buildOutboxRecord(
      String eventType, int schemaVersion, T payload, UUID orderId, UUID sagaId, UUID causationId) {
    ConveyorEnvelope<T> envelope =
        ConveyorEnvelope.of(
            eventType, schemaVersion, PRODUCER, sagaId, orderId, causationId, payload);

    @SuppressWarnings("unchecked")
    Map<String, Object> envelopeMap = objectMapper.convertValue(envelope, Map.class);

    Map<String, Object> headers =
        new LinkedHashMap<>(
            Map.of(
                "event-type",
                eventType,
                "schema-version",
                String.valueOf(schemaVersion),
                "content-type",
                "application/json"));
    String traceparent = traceparentSupport.currentTraceparent();
    if (traceparent != null) {
      headers.put("traceparent", traceparent);
    }

    return new OutboxRecord(
        UUID.randomUUID(),
        "Payment",
        orderId.toString(),
        eventType,
        KafkaTopics.SAGA_REPLIES,
        orderId.toString(),
        envelopeMap,
        headers);
  }
}
