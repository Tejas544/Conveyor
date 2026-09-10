package com.conveyor.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.contracts.events.InventoryItemPayload;
import com.conveyor.inventory.domain.Reservation;
import com.conveyor.inventory.domain.ReservationStatus;
import com.conveyor.inventory.domain.StockItem;
import com.conveyor.inventory.outbox.OutboxRecord;
import com.conveyor.inventory.outbox.OutboxRecordRepository;
import com.conveyor.inventory.repository.ReservationRepository;
import com.conveyor.inventory.repository.StockItemRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReservationReleaseIntegrationTest extends AbstractIntegrationTest {

  @Autowired private InventoryReservationService reservationService;
  @Autowired private ReservationRepository reservationRepository;
  @Autowired private StockItemRepository stockItemRepository;
  @Autowired private OutboxRecordRepository outboxRecordRepository;

  @Test
  void reserveThenReleaseRestoresReservedToItsExactPriorValue() {
    String sku = "SKU-RELEASE-1";
    stockItemRepository.saveAndFlush(new StockItem(sku, 10, 4, 2));
    int reservedBefore = stockItemRepository.findById(sku).orElseThrow().getReserved();

    UUID orderId = UUID.randomUUID();
    reservationService.handleReserveInventory(
        UUID.randomUUID(), orderId, null, null, List.of(new InventoryItemPayload(sku, 3)));
    assertThat(stockItemRepository.findById(sku).orElseThrow().getReserved())
        .isEqualTo(reservedBefore + 3);

    Reservation reservation = reservationRepository.findByOrderId(orderId).get(0);
    assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.HELD);

    reservationService.handleReleaseInventory(
        UUID.randomUUID(), orderId, null, null, List.of(reservation.getId()));

    assertThat(stockItemRepository.findById(sku).orElseThrow().getReserved())
        .isEqualTo(reservedBefore);
    Reservation released = reservationRepository.findById(reservation.getId()).orElseThrow();
    assertThat(released.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    assertThat(released.getReleasedAt()).isNotNull();

    boolean releaseReplyPublished =
        outboxRecordRepository.findAll().stream()
            .anyMatch(
                (OutboxRecord r) ->
                    r.getAggregateId().equals(orderId.toString())
                        && r.getEventType().equals("InventoryReleased"));
    assertThat(releaseReplyPublished).isTrue();
  }

  @Test
  void releasingAnAlreadyReleasedReservationDoesNotDoubleRestoreStock() {
    String sku = "SKU-RELEASE-2";
    stockItemRepository.saveAndFlush(new StockItem(sku, 10, 0, 2));
    UUID orderId = UUID.randomUUID();
    reservationService.handleReserveInventory(
        UUID.randomUUID(), orderId, null, null, List.of(new InventoryItemPayload(sku, 5)));
    Reservation reservation = reservationRepository.findByOrderId(orderId).get(0);

    reservationService.handleReleaseInventory(
        UUID.randomUUID(), orderId, null, null, List.of(reservation.getId()));
    reservationService.handleReleaseInventory(
        UUID.randomUUID(), orderId, null, null, List.of(reservation.getId()));

    assertThat(stockItemRepository.findById(sku).orElseThrow().getReserved()).isZero();
  }
}
