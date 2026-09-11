package com.conveyor.saga.service;

import com.conveyor.common.envelope.ConveyorEnvelope;
import com.conveyor.common.kafka.KafkaTopics;
import com.conveyor.common.tracing.TraceparentSupport;
import com.conveyor.contracts.events.ChargePaymentPayload;
import com.conveyor.contracts.events.InventoryItemPayload;
import com.conveyor.contracts.events.OrderCancelledPayload;
import com.conveyor.contracts.events.OrderConfirmedPayload;
import com.conveyor.contracts.events.OrderItemPayload;
import com.conveyor.contracts.events.RefundPaymentPayload;
import com.conveyor.contracts.events.ReleaseInventoryPayload;
import com.conveyor.contracts.events.ReserveInventoryPayload;
import com.conveyor.saga.config.SagaProperties;
import com.conveyor.saga.domain.IllegalSagaStateException;
import com.conveyor.saga.domain.SagaInstance;
import com.conveyor.saga.domain.SagaNotFoundException;
import com.conveyor.saga.domain.SagaState;
import com.conveyor.saga.domain.SagaStep;
import com.conveyor.saga.domain.SagaSteps;
import com.conveyor.saga.domain.StepDirection;
import com.conveyor.saga.domain.StepStatus;
import com.conveyor.saga.metrics.SagaMetrics;
import com.conveyor.saga.outbox.InboxRecord;
import com.conveyor.saga.outbox.InboxRecordId;
import com.conveyor.saga.outbox.InboxRecordRepository;
import com.conveyor.saga.outbox.OutboxRecord;
import com.conveyor.saga.outbox.OutboxRecordRepository;
import com.conveyor.saga.repository.SagaInstanceRepository;
import com.conveyor.saga.repository.SagaStepRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ARCHITECTURE.md §3 ADR-1, §7: the coordinator. Drives the one saga definition this engine knows
 * ({@code ORDER_FULFILLMENT}, {@link SagaSteps}) by sending commands via the outbox and consuming
 * replies via the inbox — the same at-least-once/exactly-once-effects pattern as every other
 * service (ADR-7, §9), just with itself as both producer and consumer.
 *
 * <p><strong>The pivot.</strong> Processing {@code PaymentCharged} commits the forward log entry
 * and transitions to {@link SagaState#CONFIRMING} in one transaction; {@link #tryConfirm} — a
 * second, separate transaction — performs the actual pivot (write {@code OrderConfirmed}, state =
 * {@code COMPLETED}). Splitting these in two is what makes {@code CONFIRMING} a real, observable,
 * crash-recoverable state rather than an instant no-op: a crash between the two transactions leaves
 * a saga in {@code CONFIRMING} with an expired deadline for {@link
 * com.conveyor.saga.scheduling.SagaTimeoutSweeper} to retry — always forward, never backward,
 * because once payment is known captured and inventory known held, completing is the only correct
 * outcome (ARCHITECTURE.md §8.3).
 *
 * <p><strong>Compensation is derived from the log, never hardcoded</strong> (§8.2, §3): {@link
 * #abortFrom} reads which {@code FORWARD} steps actually succeeded for a saga and walks backwards
 * through {@link SagaSteps#compensationFor}, which is also how an operator-initiated {@link
 * #abortSaga} reaches {@link SagaState#COMPENSATING_PAYMENT} for real — the only legitimate trigger
 * for that state, since {@link RefundPaymentPayload} requires a known {@code paymentId} that a
 * blind timeout-driven refund could never supply (see this class's package-info / CONTEXT.md's Key
 * Decisions Log for why a {@code CHARGING_PAYMENT} timeout compensates inventory only).
 */
@Service
public class SagaOrchestrationService {

  private static final Logger log = LoggerFactory.getLogger(SagaOrchestrationService.class);

  static final String CONSUMER_NAME = "saga-orchestrator";
  private static final String PRODUCER = "saga-orchestrator";

  private final SagaInstanceRepository sagaInstanceRepository;
  private final SagaStepRepository sagaStepRepository;
  private final InboxRecordRepository inboxRecordRepository;
  private final OutboxRecordRepository outboxRecordRepository;
  private final ObjectMapper objectMapper;
  private final SagaProperties properties;
  private final SagaMetrics metrics;
  private final TraceparentSupport traceparentSupport;

  public SagaOrchestrationService(
      SagaInstanceRepository sagaInstanceRepository,
      SagaStepRepository sagaStepRepository,
      InboxRecordRepository inboxRecordRepository,
      OutboxRecordRepository outboxRecordRepository,
      ObjectMapper objectMapper,
      SagaProperties properties,
      SagaMetrics metrics,
      TraceparentSupport traceparentSupport) {
    this.sagaInstanceRepository = sagaInstanceRepository;
    this.sagaStepRepository = sagaStepRepository;
    this.inboxRecordRepository = inboxRecordRepository;
    this.outboxRecordRepository = outboxRecordRepository;
    this.objectMapper = objectMapper;
    this.properties = properties;
    this.metrics = metrics;
    this.traceparentSupport = traceparentSupport;
  }

  // ---------------------------------------------------------------- OrderPlaced

  @Transactional
  public void startSaga(
      UUID eventId,
      UUID orderId,
      List<OrderItemPayload> items,
      BigDecimal totalAmount,
      String currency,
      String paymentMethodToken) {
    InboxRecordId inboxId = new InboxRecordId(eventId, CONSUMER_NAME);
    if (inboxRecordRepository.existsById(inboxId)) {
      metrics.recordInboxDuplicate();
      return;
    }

    UUID sagaId = UUID.randomUUID();
    SagaInstance saga =
        new SagaInstance(
            sagaId, orderId, SagaSteps.DEFINITION_ORDER_FULFILLMENT, SagaState.RESERVING_INVENTORY);
    saga.setTotalAmount(totalAmount);
    saga.setCurrency(currency);
    saga.setPaymentMethodToken(paymentMethodToken);
    saga.setCurrentStep(SagaSteps.RESERVE_INVENTORY);
    saga.setDeadlineAt(Instant.now().plus(properties.forwardStepTimeout()));
    sagaInstanceRepository.saveAndFlush(saga);

    appendStep(
        sagaId,
        SagaSteps.RESERVE_INVENTORY,
        StepDirection.FORWARD,
        StepStatus.STARTED,
        eventId,
        null);

    List<InventoryItemPayload> inventoryItems =
        items.stream().map(item -> new InventoryItemPayload(item.sku(), item.quantity())).toList();
    outboxRecordRepository.save(
        buildOutboxRecord(
            KafkaTopics.INVENTORY_COMMANDS,
            ReserveInventoryPayload.EVENT_TYPE,
            ReserveInventoryPayload.SCHEMA_VERSION,
            new ReserveInventoryPayload(inventoryItems),
            orderId,
            sagaId,
            eventId));

    inboxRecordRepository.save(new InboxRecord(inboxId));
  }

  // ---------------------------------------------------------------- saga.replies

  @Transactional
  public void handleInventoryReserved(
      UUID eventId, UUID orderId, List<UUID> reservationIds, List<InventoryItemPayload> items) {
    SagaInstance saga = requireSaga(orderId);
    if (!dedupe(eventId)) {
      return;
    }
    if (saga.getState() != SagaState.RESERVING_INVENTORY) {
      if (saga.getState() == SagaState.ABORTED) {
        // BUG-0026/0027 (found live via Phase 11's chaos matrix): a RESERVING_INVENTORY timeout
        // means no reply had arrived *yet* when the sweep claimed the saga — it does not mean the
        // reservation never happened. If this reply is only late (e.g. a crashed
        // SagaReplyListener's redelivery lost the race against the sweeper), inventory-service
        // really did reserve stock this saga's own log never learned about, and nothing would ever
        // release it. The saga stays ABORTED (a terminal saga's own outcome doesn't change) but the
        // reservation this reply just revealed is compensated immediately, using the reservation
        // IDs the reply carries — the one place that information ever existed.
        log.warn(
            "Saga {} already ABORTED when InventoryReserved arrived for order {} — releasing the"
                + " reservation this late reply reveals rather than orphaning it",
            saga.getId(),
            orderId);
        appendStep(
            saga.getId(),
            SagaSteps.RELEASE_INVENTORY,
            StepDirection.COMPENSATION,
            StepStatus.STARTED,
            eventId,
            Map.of("reason", "late InventoryReserved reply after saga aborted"));
        outboxRecordRepository.save(
            buildOutboxRecord(
                KafkaTopics.INVENTORY_COMMANDS,
                ReleaseInventoryPayload.EVENT_TYPE,
                ReleaseInventoryPayload.SCHEMA_VERSION,
                new ReleaseInventoryPayload(reservationIds),
                orderId,
                saga.getId(),
                eventId));
      } else {
        log.warn(
            "Ignoring InventoryReserved for saga {} in state {} (expected RESERVING_INVENTORY)",
            saga.getId(),
            saga.getState());
      }
      return;
    }

    Instant startedAt =
        stepStartedAt(saga.getId(), SagaSteps.RESERVE_INVENTORY, StepDirection.FORWARD);
    appendStep(
        saga.getId(),
        SagaSteps.RESERVE_INVENTORY,
        StepDirection.FORWARD,
        StepStatus.SUCCEEDED,
        eventId,
        Map.of(
            "reservationIds",
            reservationIds.stream().map(UUID::toString).toList(),
            "items",
            items));
    metrics.recordStepDuration(SagaSteps.RESERVE_INVENTORY, "FORWARD", startedAt);

    saga.setState(SagaState.CHARGING_PAYMENT);
    saga.setCurrentStep(SagaSteps.CHARGE_PAYMENT);
    saga.setDeadlineAt(Instant.now().plus(properties.forwardStepTimeout()));
    saga.setAttempt(0);
    sagaInstanceRepository.save(saga);

    appendStep(
        saga.getId(),
        SagaSteps.CHARGE_PAYMENT,
        StepDirection.FORWARD,
        StepStatus.STARTED,
        eventId,
        null);

    String idempotencyKey = saga.getId() + ":" + SagaSteps.CHARGE_PAYMENT;
    outboxRecordRepository.save(
        buildOutboxRecord(
            KafkaTopics.PAYMENT_COMMANDS,
            ChargePaymentPayload.EVENT_TYPE,
            ChargePaymentPayload.SCHEMA_VERSION,
            new ChargePaymentPayload(
                saga.getTotalAmount(),
                saga.getCurrency(),
                saga.getPaymentMethodToken(),
                idempotencyKey),
            orderId,
            saga.getId(),
            eventId));
  }

  @Transactional
  public void handleInventoryReservationFailed(
      UUID eventId, UUID orderId, String reason, List<Object> shortfalls) {
    SagaInstance saga = requireSaga(orderId);
    if (!dedupe(eventId)) {
      return;
    }
    if (saga.getState() != SagaState.RESERVING_INVENTORY) {
      log.warn(
          "Ignoring InventoryReservationFailed for saga {} in state {}",
          saga.getId(),
          saga.getState());
      return;
    }

    appendStep(
        saga.getId(),
        SagaSteps.RESERVE_INVENTORY,
        StepDirection.FORWARD,
        StepStatus.FAILED,
        eventId,
        Map.of("reason", reason, "shortfalls", shortfalls));

    terminateAborted(saga, OrderCancelledPayload.REASON_INVENTORY_RESERVATION_FAILED, eventId);
  }

  @Transactional
  public void handleInventoryReleased(UUID eventId, UUID orderId, List<UUID> reservationIds) {
    SagaInstance saga = requireSaga(orderId);
    if (!dedupe(eventId)) {
      return;
    }
    if (saga.getState() != SagaState.COMPENSATING_INVENTORY) {
      log.warn("Ignoring InventoryReleased for saga {} in state {}", saga.getId(), saga.getState());
      return;
    }

    Instant startedAt =
        stepStartedAt(saga.getId(), SagaSteps.RELEASE_INVENTORY, StepDirection.COMPENSATION);
    appendStep(
        saga.getId(),
        SagaSteps.RELEASE_INVENTORY,
        StepDirection.COMPENSATION,
        StepStatus.SUCCEEDED,
        eventId,
        Map.of("reservationIds", reservationIds.stream().map(UUID::toString).toList()));
    metrics.recordStepDuration(SagaSteps.RELEASE_INVENTORY, "COMPENSATION", startedAt);

    terminateAborted(saga, saga.getFailureReason(), eventId);
  }

  @Transactional
  public void handlePaymentCharged(
      UUID eventId, UUID orderId, UUID paymentId, BigDecimal amount, String gatewayReference) {
    SagaInstance saga = requireSaga(orderId);
    if (!dedupe(eventId)) {
      return;
    }
    if (saga.getState() != SagaState.CHARGING_PAYMENT) {
      if (isPastChargingPaymentViaTimeout(saga.getState())) {
        // BUG-0027 (found live via Phase 11's chaos matrix — "money moved, nobody told," exactly
        // the danger ARCHITECTURE.md §14 names as the most dangerous injection point, reached here
        // via a redelivery race rather than that specific point): a CHARGING_PAYMENT timeout is
        // handled as "no reply arrived, so nothing was charged, only inventory needs releasing" —
        // true in general, but not when this is a *late* reply for a charge that crashed and was
        // redelivered after the sweeper already gave up. Refund immediately using the paymentId
        // this reply just revealed; the saga's own outcome does not change.
        //
        // BUG-0036 (found live via Phase 13's own HPA load test, a genuine gap in BUG-0027's
        // original fix): that fix only checked SagaState.ABORTED, but RELEASE_INVENTORY
        // compensation (triggered by the same CHARGE_PAYMENT timeout that made this reply "late")
        // can itself retry for an extended period under load before the saga actually reaches
        // ABORTED — real 7-minute gap observed live, three RELEASE_INVENTORY attempts before
        // success. A late PaymentCharged reply arriving mid-compensation (state
        // COMPENSATING_INVENTORY) or after compensation itself escalated (NEEDS_INTERVENTION) hit
        // the `else` branch below and was silently dropped: charged, never refunded, no log above
        // WARN. Covering every state this saga can be in *because of* that same timeout — not just
        // its eventual terminal one — closes the gap.
        log.warn(
            "Saga {} already past CHARGING_PAYMENT (state {}) when PaymentCharged arrived for"
                + " order {} — refunding payment {} this late reply reveals rather than leaving"
                + " the customer charged",
            saga.getId(),
            saga.getState(),
            orderId,
            paymentId);
        String idempotencyKey = saga.getId() + ":" + SagaSteps.REFUND_PAYMENT;
        appendStep(
            saga.getId(),
            SagaSteps.REFUND_PAYMENT,
            StepDirection.COMPENSATION,
            StepStatus.STARTED,
            eventId,
            Map.of(
                "reason",
                "late PaymentCharged reply after CHARGE_PAYMENT timeout (state "
                    + saga.getState()
                    + ")",
                "paymentId",
                paymentId.toString()));
        outboxRecordRepository.save(
            buildOutboxRecord(
                KafkaTopics.PAYMENT_COMMANDS,
                RefundPaymentPayload.EVENT_TYPE,
                RefundPaymentPayload.SCHEMA_VERSION,
                new RefundPaymentPayload(paymentId, amount, idempotencyKey),
                orderId,
                saga.getId(),
                eventId));
      } else {
        log.warn("Ignoring PaymentCharged for saga {} in state {}", saga.getId(), saga.getState());
      }
      return;
    }

    Instant startedAt =
        stepStartedAt(saga.getId(), SagaSteps.CHARGE_PAYMENT, StepDirection.FORWARD);
    appendStep(
        saga.getId(),
        SagaSteps.CHARGE_PAYMENT,
        StepDirection.FORWARD,
        StepStatus.SUCCEEDED,
        eventId,
        Map.of(
            "paymentId", paymentId.toString(),
            "gatewayReference", gatewayReference,
            "amount", amount.toString()));
    metrics.recordStepDuration(SagaSteps.CHARGE_PAYMENT, "FORWARD", startedAt);

    saga.setState(SagaState.CONFIRMING);
    saga.setCurrentStep(SagaSteps.CONFIRM_ORDER);
    saga.setDeadlineAt(Instant.now().plus(properties.forwardStepTimeout()));
    saga.setAttempt(0);
    sagaInstanceRepository.save(saga);

    appendStep(
        saga.getId(),
        SagaSteps.CONFIRM_ORDER,
        StepDirection.FORWARD,
        StepStatus.STARTED,
        eventId,
        null);
  }

  /**
   * The pivot (ARCHITECTURE.md §7.3), deliberately its own transaction — see this class's Javadoc.
   * Idempotent: a saga already past {@code CONFIRMING} is a no-op, which is what makes it safe for
   * both the normal synchronous call site and {@link
   * com.conveyor.saga.scheduling.SagaTimeoutSweeper} to call it.
   */
  @Transactional
  public void tryConfirm(UUID sagaId) {
    SagaInstance saga =
        sagaInstanceRepository
            .findById(sagaId)
            .orElseThrow(() -> SagaNotFoundException.forSagaId(sagaId));
    if (saga.getState() != SagaState.CONFIRMING) {
      return;
    }

    Instant startedAt = stepStartedAt(sagaId, SagaSteps.CONFIRM_ORDER, StepDirection.FORWARD);
    appendStep(
        sagaId, SagaSteps.CONFIRM_ORDER, StepDirection.FORWARD, StepStatus.SUCCEEDED, null, null);
    metrics.recordStepDuration(SagaSteps.CONFIRM_ORDER, "FORWARD", startedAt);

    saga.setState(SagaState.COMPLETED);
    saga.setCurrentStep(null);
    saga.setDeadlineAt(null);
    sagaInstanceRepository.save(saga);

    outboxRecordRepository.save(
        buildOutboxRecord(
            KafkaTopics.ORDER_EVENTS,
            OrderConfirmedPayload.EVENT_TYPE,
            OrderConfirmedPayload.SCHEMA_VERSION,
            new OrderConfirmedPayload(saga.getOrderId(), Instant.now()),
            saga.getOrderId(),
            sagaId,
            null));
    metrics.recordSagaTerminal("COMPLETED", saga.getCreatedAt());
    log.info("Saga {} completed, order {} confirmed", sagaId, saga.getOrderId());
  }

  @Transactional
  public void handlePaymentFailed(UUID eventId, UUID orderId, String reason) {
    SagaInstance saga = requireSaga(orderId);
    if (!dedupe(eventId)) {
      return;
    }
    if (saga.getState() != SagaState.CHARGING_PAYMENT) {
      log.warn("Ignoring PaymentFailed for saga {} in state {}", saga.getId(), saga.getState());
      return;
    }

    appendStep(
        saga.getId(),
        SagaSteps.CHARGE_PAYMENT,
        StepDirection.FORWARD,
        StepStatus.FAILED,
        eventId,
        Map.of("reason", reason));

    beginInventoryCompensation(saga, OrderCancelledPayload.REASON_PAYMENT_DECLINED, eventId);
  }

  @Transactional
  public void handlePaymentRefunded(UUID eventId, UUID orderId, UUID paymentId) {
    SagaInstance saga = requireSaga(orderId);
    if (!dedupe(eventId)) {
      return;
    }
    if (saga.getState() != SagaState.COMPENSATING_PAYMENT) {
      log.warn("Ignoring PaymentRefunded for saga {} in state {}", saga.getId(), saga.getState());
      return;
    }

    Instant startedAt =
        stepStartedAt(saga.getId(), SagaSteps.REFUND_PAYMENT, StepDirection.COMPENSATION);
    appendStep(
        saga.getId(),
        SagaSteps.REFUND_PAYMENT,
        StepDirection.COMPENSATION,
        StepStatus.SUCCEEDED,
        eventId,
        Map.of("paymentId", paymentId.toString()));
    metrics.recordStepDuration(SagaSteps.REFUND_PAYMENT, "COMPENSATION", startedAt);

    beginInventoryCompensation(saga, saga.getFailureReason(), eventId);
  }

  // ---------------------------------------------------------------- admin operations

  @Transactional
  public SagaInstance abortSaga(UUID sagaId, String reason) {
    SagaInstance saga =
        sagaInstanceRepository
            .findById(sagaId)
            .orElseThrow(() -> SagaNotFoundException.forSagaId(sagaId));
    return abortFrom(saga, reason);
  }

  /**
   * Derives which compensations are owed from the saga's own step log rather than from a hardcoded
   * list (ARCHITECTURE.md §3, §8.2): whichever {@code FORWARD} steps most recently {@code
   * SUCCEEDED} for this saga determine where compensation resumes. This is the general mechanism
   * both the timeout-driven paths and this operator-initiated abort share.
   */
  private SagaInstance abortFrom(SagaInstance saga, String reason) {
    if (saga.getFailureReason() == null) {
      saga.setFailureReason(reason);
    }
    boolean paymentSucceeded =
        hasSucceeded(saga.getId(), SagaSteps.CHARGE_PAYMENT, StepDirection.FORWARD);
    boolean inventorySucceeded =
        hasSucceeded(saga.getId(), SagaSteps.RESERVE_INVENTORY, StepDirection.FORWARD);

    switch (saga.getState()) {
      case RESERVING_INVENTORY -> {
        appendStep(
            saga.getId(),
            SagaSteps.RESERVE_INVENTORY,
            StepDirection.FORWARD,
            StepStatus.FAILED,
            null,
            null);
        return terminateAborted(saga, reason, null);
      }
      case CHARGING_PAYMENT, CONFIRMING -> {
        if (paymentSucceeded) {
          return beginPaymentCompensation(saga, reason);
        }
        if (inventorySucceeded) {
          return beginInventoryCompensation(saga, reason, null);
        }
        return terminateAborted(saga, reason, null);
      }
      case COMPENSATING_PAYMENT, COMPENSATING_INVENTORY -> {
        // Already compensating; idempotent no-op beyond recording the reason above.
        sagaInstanceRepository.save(saga);
        return saga;
      }
      default -> throw new IllegalSagaStateException(saga.getId(), saga.getState(), "abort");
    }
  }

  @Transactional
  public SagaInstance retrySaga(UUID sagaId) {
    SagaInstance saga =
        sagaInstanceRepository
            .findById(sagaId)
            .orElseThrow(() -> SagaNotFoundException.forSagaId(sagaId));
    if (saga.getState() != SagaState.NEEDS_INTERVENTION) {
      throw new IllegalSagaStateException(saga.getId(), saga.getState(), "retry");
    }

    saga.setAttempt(0);
    if (SagaSteps.REFUND_PAYMENT.equals(saga.getCurrentStep())) {
      saga.setState(SagaState.COMPENSATING_PAYMENT);
      saga.setDeadlineAt(Instant.now().plus(properties.compensationStepTimeout()));
      sagaInstanceRepository.save(saga);
      appendStep(
          saga.getId(),
          SagaSteps.REFUND_PAYMENT,
          StepDirection.COMPENSATION,
          StepStatus.STARTED,
          null,
          null);
      outboxRecordRepository.save(buildRefundCommand(saga));
    } else {
      saga.setState(SagaState.COMPENSATING_INVENTORY);
      saga.setDeadlineAt(Instant.now().plus(properties.compensationStepTimeout()));
      sagaInstanceRepository.save(saga);
      appendStep(
          saga.getId(),
          SagaSteps.RELEASE_INVENTORY,
          StepDirection.COMPENSATION,
          StepStatus.STARTED,
          null,
          null);
      outboxRecordRepository.save(buildReleaseCommand(saga));
    }
    return saga;
  }

  /**
   * ARCHITECTURE.md §7.4 — the deadline-sweep policy, applied by {@link
   * com.conveyor.saga.scheduling.SagaTimeoutSweeper} to a saga it has already claimed with {@code
   * FOR UPDATE SKIP LOCKED} (so this method assumes the row lock is already held and does not
   * re-check the deadline itself).
   *
   * <p>A {@code CHARGING_PAYMENT} timeout compensates inventory only, never payment — {@link
   * RefundPaymentPayload} requires a {@code paymentId} this saga has never received when the reply
   * that would carry it never arrived, so there is nothing to refund by construction (see this
   * class's Javadoc and CONTEXT.md's Key Decisions Log).
   */
  @Transactional
  public void applyTimeoutPolicy(UUID sagaId) {
    SagaInstance saga =
        sagaInstanceRepository
            .findById(sagaId)
            .orElseThrow(() -> SagaNotFoundException.forSagaId(sagaId));

    switch (saga.getState()) {
      case RESERVING_INVENTORY -> {
        metrics.recordTimeout(SagaSteps.RESERVE_INVENTORY);
        appendStep(
            sagaId,
            SagaSteps.RESERVE_INVENTORY,
            StepDirection.FORWARD,
            StepStatus.TIMED_OUT,
            null,
            null);
        terminateAborted(saga, OrderCancelledPayload.REASON_RESERVE_INVENTORY_TIMEOUT, null);
      }
      case CHARGING_PAYMENT -> {
        metrics.recordTimeout(SagaSteps.CHARGE_PAYMENT);
        appendStep(
            sagaId,
            SagaSteps.CHARGE_PAYMENT,
            StepDirection.FORWARD,
            StepStatus.TIMED_OUT,
            null,
            null);
        beginInventoryCompensation(saga, OrderCancelledPayload.REASON_CHARGE_PAYMENT_TIMEOUT, null);
      }
      case CONFIRMING -> tryConfirm(sagaId);
      case COMPENSATING_INVENTORY ->
          retryOrEscalate(saga, SagaSteps.RELEASE_INVENTORY, this::buildReleaseCommand);
      case COMPENSATING_PAYMENT ->
          retryOrEscalate(saga, SagaSteps.REFUND_PAYMENT, this::buildRefundCommand);
      default ->
          log.debug(
              "Sweep claimed saga {} in terminal-adjacent state {}; nothing to do",
              sagaId,
              saga.getState());
    }
  }

  private void retryOrEscalate(
      SagaInstance saga,
      String compensationStep,
      java.util.function.Function<SagaInstance, OutboxRecord> commandBuilder) {
    int attempt = saga.getAttempt() + 1;
    metrics.recordTimeout(compensationStep);
    if (attempt >= properties.maxCompensationAttempts()) {
      appendStep(
          saga.getId(),
          compensationStep,
          StepDirection.COMPENSATION,
          StepStatus.TIMED_OUT,
          null,
          null);
      saga.setState(SagaState.NEEDS_INTERVENTION);
      saga.setDeadlineAt(null);
      sagaInstanceRepository.save(saga);
      metrics.recordSagaTerminal("NEEDS_INTERVENTION", saga.getCreatedAt());
      log.warn(
          "Saga {} exhausted {} compensation attempts for {}; escalated to NEEDS_INTERVENTION",
          saga.getId(),
          attempt,
          compensationStep);
      return;
    }

    saga.setAttempt(attempt);
    saga.setDeadlineAt(
        Instant.now().plus(properties.compensationStepTimeout().multipliedBy(attempt + 1L)));
    sagaInstanceRepository.save(saga);
    appendStep(
        saga.getId(), compensationStep, StepDirection.COMPENSATION, StepStatus.STARTED, null, null);
    outboxRecordRepository.save(commandBuilder.apply(saga));
  }

  // ---------------------------------------------------------------- shared transitions

  private SagaInstance beginInventoryCompensation(
      SagaInstance saga, String reason, UUID causationId) {
    if (saga.getFailureReason() == null) {
      saga.setFailureReason(reason);
    }
    saga.setState(SagaState.COMPENSATING_INVENTORY);
    saga.setCurrentStep(SagaSteps.RELEASE_INVENTORY);
    saga.setDeadlineAt(Instant.now().plus(properties.compensationStepTimeout()));
    saga.setAttempt(0);
    sagaInstanceRepository.save(saga);

    appendStep(
        saga.getId(),
        SagaSteps.RELEASE_INVENTORY,
        StepDirection.COMPENSATION,
        StepStatus.STARTED,
        causationId,
        null);
    outboxRecordRepository.save(buildReleaseCommand(saga));
    return saga;
  }

  private SagaInstance beginPaymentCompensation(SagaInstance saga, String reason) {
    if (saga.getFailureReason() == null) {
      saga.setFailureReason(reason);
    }
    saga.setState(SagaState.COMPENSATING_PAYMENT);
    saga.setCurrentStep(SagaSteps.REFUND_PAYMENT);
    saga.setDeadlineAt(Instant.now().plus(properties.compensationStepTimeout()));
    saga.setAttempt(0);
    sagaInstanceRepository.save(saga);

    appendStep(
        saga.getId(),
        SagaSteps.REFUND_PAYMENT,
        StepDirection.COMPENSATION,
        StepStatus.STARTED,
        null,
        null);
    outboxRecordRepository.save(buildRefundCommand(saga));
    return saga;
  }

  private SagaInstance terminateAborted(SagaInstance saga, String reason, UUID causationId) {
    saga.setState(SagaState.ABORTED);
    saga.setCurrentStep(null);
    saga.setDeadlineAt(null);
    if (saga.getFailureReason() == null) {
      saga.setFailureReason(reason);
    }
    sagaInstanceRepository.saveAndFlush(saga);

    List<String> compensatedSteps = compensatedStepNames(saga.getId());
    outboxRecordRepository.save(
        buildOutboxRecord(
            KafkaTopics.ORDER_EVENTS,
            OrderCancelledPayload.EVENT_TYPE,
            OrderCancelledPayload.SCHEMA_VERSION,
            new OrderCancelledPayload(saga.getFailureReason(), compensatedSteps),
            saga.getOrderId(),
            saga.getId(),
            causationId));
    metrics.recordSagaTerminal("ABORTED", saga.getCreatedAt());
    return saga;
  }

  // ---------------------------------------------------------------- helpers

  private SagaInstance requireSaga(UUID orderId) {
    return sagaInstanceRepository
        .findByOrderId(orderId)
        .orElseThrow(() -> SagaNotFoundException.forOrderId(orderId));
  }

  /**
   * BUG-0036: every state a saga can be in *as a direct consequence of* a {@code CHARGE_PAYMENT}
   * timeout — not just the eventual terminal one. {@code COMPENSATING_INVENTORY} covers a {@code
   * RELEASE_INVENTORY} compensation still retrying (the gap this bug closes: the original BUG-0027
   * fix only checked {@code ABORTED}); {@code NEEDS_INTERVENTION} covers that same compensation
   * having exhausted its retries and escalated (PLAN.md Phase 6) before this reply arrived. {@code
   * COMPENSATING_PAYMENT} is deliberately excluded — that state is reached only via the
   * operator-abort path (a *successful* charge already known to the saga, not a late one), an
   * unrelated trigger this method has no business reinterpreting.
   */
  private boolean isPastChargingPaymentViaTimeout(SagaState state) {
    return state == SagaState.ABORTED
        || state == SagaState.COMPENSATING_INVENTORY
        || state == SagaState.NEEDS_INTERVENTION;
  }

  private boolean dedupe(UUID eventId) {
    InboxRecordId inboxId = new InboxRecordId(eventId, CONSUMER_NAME);
    if (inboxRecordRepository.existsById(inboxId)) {
      metrics.recordInboxDuplicate();
      return false;
    }
    inboxRecordRepository.save(new InboxRecord(inboxId));
    return true;
  }

  private void appendStep(
      UUID sagaId,
      String step,
      StepDirection direction,
      StepStatus status,
      UUID correlationId,
      Map<String, Object> detail) {
    int nextSeq = sagaStepRepository.findBySagaIdOrderBySeqAsc(sagaId).size() + 1;
    sagaStepRepository.saveAndFlush(
        new SagaStep(sagaId, nextSeq, step, direction, status, correlationId, detail));
  }

  private Instant stepStartedAt(UUID sagaId, String step, StepDirection direction) {
    return sagaStepRepository.findBySagaIdOrderBySeqAsc(sagaId).stream()
        .filter(
            s ->
                s.getStep().equals(step)
                    && s.getDirection() == direction
                    && s.getStatus() == StepStatus.STARTED)
        .reduce((first, second) -> second)
        .map(SagaStep::getOccurredAt)
        .orElseGet(Instant::now);
  }

  private boolean hasSucceeded(UUID sagaId, String step, StepDirection direction) {
    return sagaStepRepository.findBySagaIdOrderBySeqAsc(sagaId).stream()
        .anyMatch(
            s ->
                s.getStep().equals(step)
                    && s.getDirection() == direction
                    && s.getStatus() == StepStatus.SUCCEEDED);
  }

  @SuppressWarnings("unchecked")
  private List<UUID> reservationIdsFromLog(UUID sagaId) {
    return sagaStepRepository.findBySagaIdOrderBySeqAsc(sagaId).stream()
        .filter(
            s ->
                SagaSteps.RESERVE_INVENTORY.equals(s.getStep())
                    && s.getStatus() == StepStatus.SUCCEEDED)
        .findFirst()
        .map(s -> (List<Object>) s.getDetail().get("reservationIds"))
        .map(list -> list.stream().map(o -> UUID.fromString(o.toString())).toList())
        .orElse(List.of());
  }

  private UUID paymentIdFromLog(UUID sagaId) {
    return sagaStepRepository.findBySagaIdOrderBySeqAsc(sagaId).stream()
        .filter(
            s ->
                SagaSteps.CHARGE_PAYMENT.equals(s.getStep())
                    && s.getStatus() == StepStatus.SUCCEEDED)
        .findFirst()
        .map(s -> UUID.fromString((String) s.getDetail().get("paymentId")))
        .orElseThrow(
            () -> new IllegalStateException("No captured payment recorded for saga " + sagaId));
  }

  private List<String> compensatedStepNames(UUID sagaId) {
    return sagaStepRepository.findBySagaIdOrderBySeqAsc(sagaId).stream()
        .filter(
            s ->
                s.getDirection() == StepDirection.COMPENSATION
                    && s.getStatus() == StepStatus.SUCCEEDED)
        .map(
            s ->
                SagaSteps.RELEASE_INVENTORY.equals(s.getStep())
                    ? SagaSteps.RESERVE_INVENTORY
                    : SagaSteps.CHARGE_PAYMENT)
        .distinct()
        .toList();
  }

  private OutboxRecord buildReleaseCommand(SagaInstance saga) {
    List<UUID> reservationIds = reservationIdsFromLog(saga.getId());
    return buildOutboxRecord(
        KafkaTopics.INVENTORY_COMMANDS,
        ReleaseInventoryPayload.EVENT_TYPE,
        ReleaseInventoryPayload.SCHEMA_VERSION,
        new ReleaseInventoryPayload(reservationIds),
        saga.getOrderId(),
        saga.getId(),
        null);
  }

  private OutboxRecord buildRefundCommand(SagaInstance saga) {
    UUID paymentId = paymentIdFromLog(saga.getId());
    String idempotencyKey = saga.getId() + ":" + SagaSteps.REFUND_PAYMENT;
    return buildOutboxRecord(
        KafkaTopics.PAYMENT_COMMANDS,
        RefundPaymentPayload.EVENT_TYPE,
        RefundPaymentPayload.SCHEMA_VERSION,
        new RefundPaymentPayload(paymentId, saga.getTotalAmount(), idempotencyKey),
        saga.getOrderId(),
        saga.getId(),
        null);
  }

  private <T> OutboxRecord buildOutboxRecord(
      String topic,
      String eventType,
      int schemaVersion,
      T payload,
      UUID orderId,
      UUID sagaId,
      UUID causationId) {
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
        "SagaInstance",
        orderId.toString(),
        eventType,
        topic,
        orderId.toString(),
        envelopeMap,
        headers);
  }
}
