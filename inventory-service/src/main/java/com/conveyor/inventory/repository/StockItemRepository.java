package com.conveyor.inventory.repository;

import com.conveyor.inventory.domain.StockItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

  /**
   * {@code POST /inventory/{sku}/adjust} (ADR-9's guard applied to admin stock corrections too):
   * zero rows means the delta would drop {@code on_hand} below {@code reserved}.
   */
  @Modifying
  @Query(
      "update StockItem s set s.onHand = s.onHand + :delta, s.version = s.version + 1 "
          + "where s.sku = :sku and (s.onHand + :delta - s.reserved) >= 0")
  int adjustOnHand(@Param("sku") String sku, @Param("delta") int delta);

  @Query(
      "select s from StockItem s where (:lowStock = false or (s.onHand - s.reserved) <= s.reorderLevel)")
  Page<StockItem> search(@Param("lowStock") boolean lowStock, Pageable pageable);

  /**
   * Fulfillment: stock that was held now permanently leaves {@code on_hand} (ARCHITECTURE.md §13's
   * INV-ORD-01/INV-INV-03 — the reservation's terminal state on a confirmed order is {@code
   * COMMITTED}, not an eternal {@code HELD}). Guarded on {@code reserved >= :qty} the same way
   * {@link #reserve} is guarded on availability; since {@code on_hand - reserved >= 0} always holds
   * (the table's own {@code CHECK}), {@code reserved >= qty} already implies {@code on_hand >=
   * qty}, so subtracting {@code qty} from both columns equally can never violate that constraint.
   */
  @Modifying
  @Query(
      "update StockItem s set s.onHand = s.onHand - :qty, s.reserved = s.reserved - :qty, "
          + "s.version = s.version + 1 where s.sku = :sku and s.reserved >= :qty")
  int commit(@Param("sku") String sku, @Param("qty") int qty);
}
