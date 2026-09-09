package com.conveyor.inventory.repository;

import com.conveyor.inventory.domain.StockItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockItemRepository extends JpaRepository<StockItem, String> {

  /**
   * ADR-9: the guarded conditional {@code UPDATE} that makes oversell structurally impossible. Zero
   * rows affected means insufficient stock — no lock is held across a round trip.
   */
  @Modifying
  @Query(
      "update StockItem s set s.reserved = s.reserved + :qty, s.version = s.version + 1 "
          + "where s.sku = :sku and (s.onHand - s.reserved) >= :qty")
  int reserve(@Param("sku") String sku, @Param("qty") int qty);

  /** The compensating counterpart of {@link #reserve}, released stock restores exactly. */
  @Modifying
  @Query(
      "update StockItem s set s.reserved = s.reserved - :qty, s.version = s.version + 1 "
          + "where s.sku = :sku")
  int release(@Param("sku") String sku, @Param("qty") int qty);
}
