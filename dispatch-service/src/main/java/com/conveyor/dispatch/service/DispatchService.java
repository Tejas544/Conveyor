package com.conveyor.dispatch.service;

import com.conveyor.common.envelope.ConveyorEnvelope;
import com.conveyor.common.kafka.KafkaTopics;
import com.conveyor.common.tracing.TraceparentSupport;
import com.conveyor.contracts.events.ShipmentCreatedPayload;
import com.conveyor.dispatch.domain.Shipment;
import com.conveyor.dispatch.notification.NotificationDocument;
import com.conveyor.dispatch.notification.NotificationRepository;
import com.conveyor.dispatch.outbox.InboxRecord;
import com.conveyor.dispatch.outbox.InboxRecordId;
import com.conveyor.dispatch.outbox.InboxRecordRepository;
import com.conveyor.dispatch.outbox.OutboxRecord;
import com.conveyor.dispatch.outbox.OutboxRecordRepository;
import com.conveyor.dispatch.repository.ShipmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ARCHITECTURE.md §5.5, §7.3, §14: handles {@code OrderConfirmed}, the pivot event. Dispatch is a
 * <em>retriable</em>, not compensatable, post-pivot step — PLAN.md Phase 7's "one inbox-guarded
 * unit of work per store, with the Mongo write retried independently."
 *
 * <p>The two stores use two different idempotency mechanisms because they cannot share one
 * transaction: {@link #recordShipment} is inbox-guarded the same way every other consumer in this
 * project is (a Postgres {@code inbox} row keyed on the envelope's {@code eventId}), and is a
 * genuine no-op on redelivery. {@link #writeNotification} instead upserts a MongoDB document keyed
 * on a deterministic id derived from {@code orderId} — Mongo has no inbox table of its own here, so
 * redelivery re-running the write is made safe by making the write itself idempotent (same input →
 * same final document) rather than by remembering that it already ran.
 */
@Service
public class DispatchService {

  static final String CONSUMER_NAME = "dispatch-service";
  private static final Logger log = LoggerFactory.getLogger(DispatchService.class);
  private static final String PRODUCER = "dispatch-service";
  private static final String CARRIER = "STANDARD";
  private static final String NOTIFICATION_CHANNEL = "EMAIL";
  private static final String NOTIFICATION_TEMPLATE = "order-confirmed";
  private static final String NOTIFICATION_RECIPIENT_PLACEHOLDER = "customer@example.test";

  private final ShipmentRepository shipmentRepository;
  private final NotificationRepository notificationRepository;
  private final InboxRecordRepository inboxRecordRepository;
  private final OutboxRecordRepository outboxRecordRepository;
  private final ObjectMapper objectMapper;
  private final TraceparentSupport traceparentSupport;
  private final Counter inboxDuplicatesCounter;

  public DispatchService(
      ShipmentRepository shipmentRepository,
      NotificationRepository notificationRepository,
      InboxRecordRepository inboxRecordRepository,
      OutboxRecordRepository outboxRecordRepository,
      ObjectMapper objectMapper,
      TraceparentSupport traceparentSupport,
      MeterRegistry meterRegistry) {
    this.shipmentRepository = shipmentRepository;
    this.notificationRepository = notificationRepository;
    this.inboxRecordRepository = inboxRecordRepository;
    this.outboxRecordRepository = outboxRecordRepository;
    this.objectMapper = objectMapper;
    this.traceparentSupport = traceparentSupport;
    this.inboxDuplicatesCounter =
        Counter.builder("conveyor_inbox_duplicates_total")
            .tag("consumer", CONSUMER_NAME)
            .register(meterRegistry);
  }

  /**
   * Creates the {@code shipments} row and its {@code ShipmentCreated} outbox row, guarded by the
   * standard inbox pattern (§9) — a no-op on any redelivery of the same {@code eventId}, since the
   * first delivery already created both rows atomically with this one.
   */
  @Transactional
  public void recordShipment(UUID eventId, UUID orderId, UUID sagaId) {
    InboxRecordId inboxId = new InboxRecordId(eventId, CONSUMER_NAME);
    if (inboxRecordRepository.existsById(inboxId)) {
      inboxDuplicatesCounter.increment();
      return;
    }

    Shipment shipment =
        new Shipment(UUID.randomUUID(), orderId, CARRIER, "TRK-" + UUID.randomUUID(), "CREATED");
    shipmentRepository.save(shipment);
    outboxRecordRepository.save(buildShipmentCreatedReply(orderId, sagaId, eventId, shipment));
    inboxRecordRepository.save(new InboxRecord(inboxId));
    log.info("Shipment {} created for order {}", shipment.getTrackingNumber(), orderId);
  }

  /**
   * Writes the notification log entry. Deliberately not gated by the Postgres inbox: {@code id} is
   * deterministic ({@code orderId} plus the notification kind), so {@link
   * NotificationRepository#save} — which upserts rather than inserts when the id is caller-assigned
   * — produces exactly the same final document whether this is the first attempt or the fifth. That
   * is what "retried independently" (PLAN.md Phase 7) means in practice: this method can be re-run
   * from scratch, by redelivery or by a future retry mechanism, with no memory of prior attempts,
   * and still end up with exactly one notification per order.
   */
  public void writeNotification(UUID orderId, Instant confirmedAt) {
    String id = orderId + ":ORDER_CONFIRMED";
    String renderedBody =
        "Your order "
            + orderId
            + " has been confirmed as of "
            + confirmedAt
            + " and is being prepared for shipment.";
    notificationRepository.save(
        new NotificationDocument(
            id,
            orderId.toString(),
            NOTIFICATION_CHANNEL,
            NOTIFICATION_TEMPLATE,
            NOTIFICATION_RECIPIENT_PLACEHOLDER,
            renderedBody,
            "SENT",
            Instant.now()));
  }

  private OutboxRecord buildShipmentCreatedReply(
      UUID orderId, UUID sagaId, UUID causationId, Shipment shipment) {
    ShipmentCreatedPayload payload =
        new ShipmentCreatedPayload(
            shipment.getId(), shipment.getCarrier(), shipment.getTrackingNumber());
    ConveyorEnvelope<ShipmentCreatedPayload> envelope =
        ConveyorEnvelope.of(
            ShipmentCreatedPayload.EVENT_TYPE,
            ShipmentCreatedPayload.SCHEMA_VERSION,
            PRODUCER,
            sagaId,
            orderId,
            causationId,
            payload);

    @SuppressWarnings("unchecked")
    Map<String, Object> envelopeMap = objectMapper.convertValue(envelope, Map.class);

    Map<String, Object> headers =
        new LinkedHashMap<>(
            Map.of(
                "event-type",
                ShipmentCreatedPayload.EVENT_TYPE,
                "schema-version",
                String.valueOf(ShipmentCreatedPayload.SCHEMA_VERSION),
                "content-type",
                "application/json"));
    String traceparent = traceparentSupport.currentTraceparent();
    if (traceparent != null) {
      headers.put("traceparent", traceparent);
    }

    return new OutboxRecord(
        UUID.randomUUID(),
        "Shipment",
        orderId.toString(),
        ShipmentCreatedPayload.EVENT_TYPE,
        KafkaTopics.DISPATCH_EVENTS,
        orderId.toString(),
        envelopeMap,
        headers);
  }
}
