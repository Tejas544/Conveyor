package com.conveyor.inventory.service;

import com.conveyor.inventory.domain.Reservation;
import com.conveyor.inventory.domain.SkuNotFoundException;
import com.conveyor.inventory.domain.StockAdjustment;
import com.conveyor.inventory.domain.StockAdjustmentRejectedException;
import com.conveyor.inventory.domain.StockItem;
import com.conveyor.inventory.repository.ReservationRepository;
import com.conveyor.inventory.repository.StockAdjustmentRepository;
import com.conveyor.inventory.repository.StockItemRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** ARCHITECTURE.md §10.3: the read side and the admin stock-correction endpoint. */
@Service
public class InventoryService {

  private final StockItemRepository stockItemRepository;
  private final ReservationRepository reservationRepository;
  private final StockAdjustmentRepository stockAdjustmentRepository;

  public InventoryService(
      StockItemRepository stockItemRepository,
      ReservationRepository reservationRepository,
      StockAdjustmentRepository stockAdjustmentRepository) {
    this.stockItemRepository = stockItemRepository;
    this.reservationRepository = reservationRepository;
    this.stockAdjustmentRepository = stockAdjustmentRepository;
  }

  @Transactional(readOnly = true)
  public Page<StockItem> search(boolean lowStock, Pageable pageable) {
    return stockItemRepository.search(lowStock, pageable);
  }

  @Transactional(readOnly = true)
  public StockItem getStockItem(String sku) {
    return stockItemRepository.findById(sku).orElseThrow(() -> new SkuNotFoundException(sku));
  }

  @Transactional(readOnly = true)
  public List<Reservation> getReservations(String sku) {
    if (!stockItemRepository.existsById(sku)) {
      throw new SkuNotFoundException(sku);
    }
    return reservationRepository.findBySku(sku);
  }

  /**
   * ADR-9's guard applied to admin corrections too — an adjustment cannot drop stock below what's
   * held.
   */
  @Transactional
  public StockItem adjust(String sku, int delta, String reason, String actor) {
    if (!stockItemRepository.existsById(sku)) {
      throw new SkuNotFoundException(sku);
    }
    int updated = stockItemRepository.adjustOnHand(sku, delta);
    if (updated == 0) {
      throw new StockAdjustmentRejectedException(sku, delta);
    }
    stockAdjustmentRepository.save(
        new StockAdjustment(UUID.randomUUID(), sku, delta, reason, actor));
    return stockItemRepository.findById(sku).orElseThrow();
  }
}
