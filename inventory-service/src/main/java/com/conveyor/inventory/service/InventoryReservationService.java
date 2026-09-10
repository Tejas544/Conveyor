package com.conveyor.inventory.service;

import com.conveyor.common.envelope.ConveyorEnvelope;
import com.conveyor.common.kafka.KafkaTopics;
import com.conveyor.contracts.events.InventoryItemPayload;
import com.conveyor.contracts.events.InventoryReleasedPayload;
import com.conveyor.contracts.events.InventoryReservationFailedPayload;
import com.conveyor.contracts.events.InventoryReservedPayload;
import com.conveyor.contracts.events.ShortfallPayload;
import com.conveyor.inventory.domain.Reservation;
import com.conveyor.inventory.domain.ReservationStatus;
import com.conveyor.inventory.domain.StockItem;
import com.conveyor.inventory.outbox.InboxRecord;
import com.conveyor.inventory.outbox.InboxRecordId;
import com.conveyor.inventory.outbox.InboxRecordRepository;
import com.conveyor.inventory.outbox.OutboxRecord;
import com.conveyor.inventory.outbox.OutboxRecordRepository;
import com.conveyor.inventory.repository.ReservationRepository;
import com.conveyor.inventory.repository.StockItemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ARCHITECTURE.md §7.3, §9, ADR-9: handles {@code ReserveInventory}/{@code ReleaseInventory}
 * commands. Reservation is all-or-nothing across a multi-SKU order and inbox-deduplicated, with the
 * business write and the outbox reply committed in the same local transaction (ADR-7) — this is the
 * second use of {@link com.conveyor.common.outbox.OutboxPoller}, unchanged from order-service.
 */
@Service
public class InventoryReservationService {

  static final String CONSUMER_NAME = "inventory-service";
  private static final String PRODUCER = "inventory-service";

  private final StockItemRepository stockItemRepository;
  private final ReservationRepository reservationRepository;
  private final InboxRecordRepository inboxRecordRepository;
  private final OutboxRecordRepository outboxRecordRepository;
  private final ObjectMapper objectMapper;
  private final Counter inboxDuplicatesCounter;

  public InventoryReservationService(
      StockItemRepository stockItemRepository,
      ReservationRepository reservationRepository,
      InboxRecordRepository inboxRecordRepository,
      OutboxRecordRepository outboxRecordRepository,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry) {
    this.stockItemRepository = stockItemRepository;
    this.reservationRepository = reservationRepository;
    this.inboxRecordRepository = inboxRecordRepository;
    this.outboxRecordRepository = outboxRecordRepository;
    this.objectMapper = objectMapper;
    this.inboxDuplicatesCounter =
        Counter.builder("conveyor_inbox_duplicates_total")
            .tag("consumer", CONSUMER_NAME)
            .register(meterRegistry);
  }

  /**
   * All-or-nothing across every item: a guarded conditional {@code UPDATE} per SKU (ADR-9),
   * explicitly undone with its own compensating {@code UPDATE} for every SKU already reserved in
   * this attempt the moment one SKU comes up short — never a thrown-and-rolled-back transaction,
   * because the failure *outcome* still has to commit alongside the inbox/outbox rows in this same
   * local transaction (a rollback would discard those too).
   *
   * <p>Redelivery of an already-processed command (an inbox hit whose reservations still exist)
   * does not repeat the business write — it reconstructs the reply from the order's current
   * reservations and re-emits it, which is what "the same command delivered 3× produces one
   * reservation and 3 identical replies" (PLAN.md Phase 4) means in practice: identical in content,
   * not in eventId.
   */
  @Transactional
  public void handleReserveInventory(
      UUID eventId, UUID orderId, UUID sagaId, UUID causationId, List<InventoryItemPayload> items) {
    InboxRecordId inboxId = new InboxRecordId(eventId, CONSUMER_NAME);
    boolean duplicate = inboxRecordRepository.existsById(inboxId);
    if (duplicate) {
      inboxDuplicatesCounter.increment();
    }

    List<String> requestedSkus = items.stream().map(InventoryItemPayload::sku).toList();
    List<Reservation> existing =
        reservationRepository.findByOrderId(orderId).stream()
            .filter(r -> r.getStatus() != ReservationStatus.RELEASED)
            .toList();
    Set<String> existingSkus =
        existing.stream().map(Reservation::getSku).collect(Collectors.toSet());

    OutboxRecord reply;
    if (!existing.isEmpty() && existingSkus.containsAll(requestedSkus)) {
      List<UUID> reservationIds = existing.stream().map(Reservation::getId).toList();
      reply = buildReservedReply(orderId, sagaId, causationId, reservationIds, items);
    } else {
      reply = attemptFreshReservation(orderId, sagaId, causationId, items);
    }

    if (!duplicate) {
      inboxRecordRepository.save(new InboxRecord(inboxId));
    }
    outboxRecordRepository.save(reply);
  }

  private OutboxRecord attemptFreshReservation(
      UUID orderId, UUID sagaId, UUID causationId, List<InventoryItemPayload> items) {
    List<String> unknownSkus =
        items.stream()
            .map(InventoryItemPayload::sku)
            .filter(sku -> !stockItemRepository.existsById(sku))
            .toList();
    if (!unknownSkus.isEmpty()) {
      List<ShortfallPayload> shortfalls =
          unknownSkus.stream()
              .map(
                  sku -> {
                    int requested = requestedQuantity(items, sku);
                    return new ShortfallPayload(sku, requested, 0);
                  })
              .toList();
      return buildFailedReply(
          orderId,
          sagaId,
          causationId,
          InventoryReservationFailedPayload.REASON_UNKNOWN_SKU,
          shortfalls);
    }

    List<InventoryItemPayload> reservedSoFar = new ArrayList<>();
    boolean shortfallHit = false;
    for (InventoryItemPayload item : items) {
      int updated = stockItemRepository.reserve(item.sku(), item.quantity());
      if (updated == 0) {
        shortfallHit = true;
        break;
      }
      reservedSoFar.add(item);
    }

    if (shortfallHit) {
      for (InventoryItemPayload reserved : reservedSoFar) {
        stockItemRepository.release(reserved.sku(), reserved.quantity());
      }
      List<ShortfallPayload> shortfalls = new ArrayList<>();
      for (InventoryItemPayload item : items) {
        StockItem stockItem = stockItemRepository.findById(item.sku()).orElseThrow();
        if (stockItem.getAvailable() < item.quantity()) {
          shortfalls.add(
              new ShortfallPayload(item.sku(), item.quantity(), stockItem.getAvailable()));
        }
      }
      return buildFailedReply(
          orderId,
          sagaId,
          causationId,
          InventoryReservationFailedPayload.REASON_INSUFFICIENT_STOCK,
          shortfalls);
    }

    List<UUID> reservationIds = new ArrayList<>();
    for (InventoryItemPayload item : items) {
      Reservation reservation =
          new Reservation(
              UUID.randomUUID(), orderId, item.sku(), item.quantity(), ReservationStatus.HELD);
      reservationRepository.save(reservation);
      reservationIds.add(reservation.getId());
    }
    return buildReservedReply(orderId, sagaId, causationId, reservationIds, items);
  }

  private int requestedQuantity(List<InventoryItemPayload> items, String sku) {
    return items.stream()
        .filter(item -> item.sku().equals(sku))
        .findFirst()
        .orElseThrow()
        .quantity();
  }

  /**
   * Idempotent by construction: releasing an already-{@code RELEASED} or unknown reservation is a
   * no-op, so redelivery never double-restores stock (PLAN.md Phase 4's "no-op success, not an
   * error" requirement) with no extra bookkeeping beyond the guard already in the loop below.
   */
  @Transactional
  public void handleReleaseInventory(
      UUID eventId, UUID orderId, UUID sagaId, UUID causationId, List<UUID> reservationIds) {
    InboxRecordId inboxId = new InboxRecordId(eventId, CONSUMER_NAME);
    boolean duplicate = inboxRecordRepository.existsById(inboxId);
    if (duplicate) {
      inboxDuplicatesCounter.increment();
    }

    for (UUID reservationId : reservationIds) {
      reservationRepository
          .findById(reservationId)
          .filter(reservation -> reservation.getStatus() == ReservationStatus.HELD)
          .ifPresent(
              reservation -> {
                stockItemRepository.release(reservation.getSku(), reservation.getQuantity());
                reservation.setStatus(ReservationStatus.RELEASED);
                reservation.setReleasedAt(Instant.now());
                reservationRepository.save(reservation);
              });
    }

    if (!duplicate) {
      inboxRecordRepository.save(new InboxRecord(inboxId));
    }
    outboxRecordRepository.save(buildReleasedReply(orderId, sagaId, causationId, reservationIds));
  }

  private OutboxRecord buildReservedReply(
      UUID orderId,
      UUID sagaId,
      UUID causationId,
      List<UUID> reservationIds,
      List<InventoryItemPayload> items) {
    InventoryReservedPayload payload = new InventoryReservedPayload(reservationIds, items);
    return buildOutboxRecord(
        InventoryReservedPayload.EVENT_TYPE,
        InventoryReservedPayload.SCHEMA_VERSION,
        payload,
        orderId,
        sagaId,
        causationId);
  }

  private OutboxRecord buildFailedReply(
      UUID orderId,
      UUID sagaId,
      UUID causationId,
      String reason,
      List<ShortfallPayload> shortfalls) {
    InventoryReservationFailedPayload payload =
        new InventoryReservationFailedPayload(reason, shortfalls);
    return buildOutboxRecord(
        InventoryReservationFailedPayload.EVENT_TYPE,
        InventoryReservationFailedPayload.SCHEMA_VERSION,
        payload,
        orderId,
        sagaId,
        causationId);
  }

  private OutboxRecord buildReleasedReply(
      UUID orderId, UUID sagaId, UUID causationId, List<UUID> reservationIds) {
    InventoryReleasedPayload payload = new InventoryReleasedPayload(reservationIds);
    return buildOutboxRecord(
        InventoryReleasedPayload.EVENT_TYPE,
        InventoryReleasedPayload.SCHEMA_VERSION,
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
        Map.of(
            "event-type",
            eventType,
            "schema-version",
            String.valueOf(schemaVersion),
            "content-type",
            "application/json");

    return new OutboxRecord(
        UUID.randomUUID(),
        "StockItem",
        orderId.toString(),
        eventType,
        KafkaTopics.SAGA_REPLIES,
        orderId.toString(),
        envelopeMap,
        headers);
  }
}
