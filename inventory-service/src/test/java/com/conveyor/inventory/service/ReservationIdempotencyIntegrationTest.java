package com.conveyor.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.contracts.events.InventoryItemPayload;
import com.conveyor.inventory.domain.Reservation;
import com.conveyor.inventory.domain.StockItem;
import com.conveyor.inventory.outbox.OutboxRecord;
import com.conveyor.inventory.outbox.OutboxRecordRepository;
import com.conveyor.inventory.repository.ReservationRepository;
import com.conveyor.inventory.repository.StockItemRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * PLAN.md Phase 4: the same {@code ReserveInventory} command (same {@code eventId}) delivered 3×
 * produces one reservation and 3 identical replies — identical in content (same {@code
 * reservationIds}), not in message identity — and {@code conveyor_inbox_duplicates_total}
 * increments by exactly 2 (the two redeliveries, not the first delivery).
 */
class ReservationIdempotencyIntegrationTest extends AbstractIntegrationTest {

  @Autowired private InventoryReservationService reservationService;
  @Autowired private ReservationRepository reservationRepository;
  @Autowired private OutboxRecordRepository outboxRecordRepository;
  @Autowired private StockItemRepository stockItemRepository;
  @Autowired private MeterRegistry meterRegistry;

  @Test
  void sameCommandDeliveredThreeTimesProducesOneReservationAndThreeIdenticalReplies() {
    String sku = "SKU-IDEMP-1";
    stockItemRepository.saveAndFlush(new StockItem(sku, 10, 0, 2));
    UUID orderId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    List<InventoryItemPayload> items = List.of(new InventoryItemPayload(sku, 3));

    double before = duplicatesCount();

    for (int i = 0; i < 3; i++) {
      reservationService.handleReserveInventory(eventId, orderId, null, null, items);
    }

    assertThat(duplicatesCount() - before).isEqualTo(2.0);

    List<Reservation> reservations = reservationRepository.findByOrderId(orderId);
    assertThat(reservations).hasSize(1);
    assertThat(stockItemRepository.findById(sku).orElseThrow().getReserved()).isEqualTo(3);

    List<OutboxRecord> replies =
        outboxRecordRepository.findAll().stream()
            .filter(
                r ->
                    r.getAggregateId().equals(orderId.toString())
                        && r.getEventType().equals("InventoryReserved"))
            .toList();
    assertThat(replies).hasSize(3);

    Set<Object> distinctReservationIdLists = new HashSet<>();
    for (OutboxRecord reply : replies) {
      @SuppressWarnings("unchecked")
      Map<String, Object> payload = (Map<String, Object>) reply.getPayload().get("payload");
      distinctReservationIdLists.add(payload.get("reservationIds"));
    }
    assertThat(distinctReservationIdLists)
        .as("all 3 replies carry the same reservationIds")
        .hasSize(1);
  }

  private double duplicatesCount() {
    var counter =
        meterRegistry
            .find("conveyor_inbox_duplicates_total")
            .tag("consumer", "inventory-service")
            .counter();
    return counter == null ? 0.0 : counter.count();
  }
}
