package com.conveyor.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/** ARCHITECTURE.md §5.3. Audit trail for {@code POST /inventory/{sku}/adjust} (ADMIN only). */
@Entity
@Table(name = "stock_adjustments")
public class StockAdjustment {

  @Id private UUID id;

  @Column(nullable = false)
  private String sku;

  @Column(nullable = false)
  private int delta;

  @Column(nullable = false)
  private String reason;

  @Column(nullable = false)
  private String actor;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected StockAdjustment() {}

  public StockAdjustment(UUID id, String sku, int delta, String reason, String actor) {
    this.id = id;
    this.sku = sku;
    this.delta = delta;
    this.reason = reason;
    this.actor = actor;
  }

  public UUID getId() {
    return id;
  }

  public String getSku() {
    return sku;
  }

  public int getDelta() {
    return delta;
  }

  public String getReason() {
    return reason;
  }

  public String getActor() {
    return actor;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
