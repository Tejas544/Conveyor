package com.conveyor.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.contracts.events.InventoryItemPayload;
import com.conveyor.inventory.domain.Reservation;
import com.conveyor.inventory.domain.ReservationStatus;
import com.conveyor.inventory.domain.StockItem;
import com.conveyor.inventory.repository.ReservationRepository;
import com.conveyor.inventory.repository.StockItemRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * BUG-0021 / ARCHITECTURE.md §13 INV-ORD-01: before {@link
 * InventoryReservationService#handleOrderConfirmed} existed, nothing in this codebase ever set
 * {@link ReservationStatus#COMMITTED} — a confirmed order's reservation stayed {@code HELD}
 * forever, which would make the catalogue's headline invariant unimplementable.
 */
class OrderConfirmedCommitIntegrationTest extends AbstractIntegrationTest {

  @Autowired private InventoryReservationService reservationService;
  @Autowired private ReservationRepository reservationRepository;
  @Autowired private StockItemRepository stockItemRepository;

  @Test
  void orderConfirmedCommitsTheHeldReservationAndPermanentlyRemovesStockFromOnHand() {
    String sku = "SKU-COMMIT-1";
    stockItemRepository.saveAndFlush(new StockItem(sku, 10, 0, 2));
    UUID orderId = UUID.randomUUID();
    reservationService.handleReserveInventory(
        UUID.randomUUID(), orderId, null, null, List.of(new InventoryItemPayload(sku, 4)));

    reservationService.handleOrderConfirmed(UUID.randomUUID(), orderId);

    StockItem stockItem = stockItemRepository.findById(sku).orElseThrow();
    assertThat(stockItem.getOnHand()).isEqualTo(6);
    assertThat(stockItem.getReserved()).isZero();
    Reservation reservation = reservationRepository.findByOrderId(orderId).get(0);
    assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.COMMITTED);
  }

  @Test
  void redeliveredOrderConfirmedDoesNotDoubleCommit() {
    String sku = "SKU-COMMIT-2";
    stockItemRepository.saveAndFlush(new StockItem(sku, 10, 0, 2));
    UUID orderId = UUID.randomUUID();
    reservationService.handleReserveInventory(
        UUID.randomUUID(), orderId, null, null, List.of(new InventoryItemPayload(sku, 4)));

    UUID eventId = UUID.randomUUID();
    reservationService.handleOrderConfirmed(eventId, orderId);
    reservationService.handleOrderConfirmed(eventId, orderId);
    reservationService.handleOrderConfirmed(eventId, orderId);

    StockItem stockItem = stockItemRepository.findById(sku).orElseThrow();
    assertThat(stockItem.getOnHand()).isEqualTo(6);
    assertThat(stockItem.getReserved()).isZero();
  }

  @Test
  void orderConfirmedForAnOrderWithNoHeldReservationsIsAHarmlessNoOp() {
    reservationService.handleOrderConfirmed(UUID.randomUUID(), UUID.randomUUID());
    // No exception, nothing to assert on — this is the "unrelated/unknown order" no-op path.
  }
}
