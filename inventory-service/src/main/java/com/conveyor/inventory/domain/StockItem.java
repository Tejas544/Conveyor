package com.conveyor.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * ARCHITECTURE.md §5.3, ADR-9. {@code sku} is the primary key. The table {@code check (on_hand -
 * reserved >= 0)} constraint (belt) backs the guarded conditional {@code UPDATE} used to reserve
 * stock (braces) — oversell is structurally impossible, not merely detected.
 */
@Entity
@Table(name = "stock_items")
public class StockItem {

  @Id private String sku;

  @Column(name = "on_hand", nullable = false)
  private int onHand;

  @Column(nullable = false)
  private int reserved;

  @Column(name = "reorder_level", nullable = false)
  private int reorderLevel;

  @Version private long version;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected StockItem() {}

  public StockItem(String sku, int onHand, int reserved, int reorderLevel) {
    this.sku = sku;
    this.onHand = onHand;
    this.reserved = reserved;
    this.reorderLevel = reorderLevel;
  }

  public String getSku() {
    return sku;
  }

  public int getOnHand() {
    return onHand;
  }

  public void setOnHand(int onHand) {
    this.onHand = onHand;
  }

  public int getReserved() {
    return reserved;
  }

  public void setReserved(int reserved) {
    this.reserved = reserved;
  }

  public int getAvailable() {
    return onHand - reserved;
  }

  public int getReorderLevel() {
    return reorderLevel;
  }

  public long getVersion() {
    return version;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
