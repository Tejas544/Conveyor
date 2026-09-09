package com.conveyor.inventory.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.inventory.domain.StockItem;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * PLAN.md Phase 2: repository CRUD for the stock ledger, plus the oversell constraint proof — an
 * {@code UPDATE} driving {@code reserved} past {@code on_hand} raises a constraint violation
 * (ARCHITECTURE.md §5.3, ADR-9). Phase 4 covers the concurrency property (N threads, one winner);
 * this test proves the belt-and-braces table constraint underneath it in isolation.
 */
class StockItemRepositoryIntegrationTest extends AbstractIntegrationTest {

  @Autowired private StockItemRepository stockItemRepository;
  @Autowired private EntityManager entityManager;

  @Test
  @Transactional
  void savesAndReadsBack() {
    stockItemRepository.saveAndFlush(new StockItem("SKU-CRUD-1", 100, 10, 5));
    entityManager.clear();

    StockItem found = stockItemRepository.findById("SKU-CRUD-1").orElseThrow();
    assertThat(found.getOnHand()).isEqualTo(100);
    assertThat(found.getReserved()).isEqualTo(10);
    assertThat(found.getAvailable()).isEqualTo(90);
  }

  @Test
  @Transactional
  void guardedReserveSucceedsWhenStockAvailable() {
    stockItemRepository.saveAndFlush(new StockItem("SKU-RES-1", 10, 0, 2));

    int updated = stockItemRepository.reserve("SKU-RES-1", 4);
    entityManager.clear();

    assertThat(updated).isEqualTo(1);
    assertThat(stockItemRepository.findById("SKU-RES-1").orElseThrow().getReserved()).isEqualTo(4);
  }

  @Test
  @Transactional
  void guardedReserveIsANoOpWhenInsufficientStock() {
    stockItemRepository.saveAndFlush(new StockItem("SKU-RES-2", 3, 2, 2));

    int updated = stockItemRepository.reserve("SKU-RES-2", 5);
    entityManager.clear();

    assertThat(updated).isZero();
    assertThat(stockItemRepository.findById("SKU-RES-2").orElseThrow().getReserved()).isEqualTo(2);
  }

  @Test
  @Transactional
  void directUpdateThatOversellsViolatesTheCheckConstraint() {
    stockItemRepository.saveAndFlush(new StockItem("SKU-OVERSELL-1", 5, 5, 2));
    entityManager.flush();

    // Deliberately bypass the guarded UPDATE to prove the table constraint itself — not just the
    // application-level guard — makes oversell impossible.
    assertThatThrownBy(
            () ->
                entityManager
                    .createNativeQuery(
                        "update stock_items set reserved = reserved + 1 where sku = :sku")
                    .setParameter("sku", "SKU-OVERSELL-1")
                    .executeUpdate())
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("stock_items");
  }
}
