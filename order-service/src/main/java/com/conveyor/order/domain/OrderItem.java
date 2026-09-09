package com.conveyor.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/** ARCHITECTURE.md §5.1. One line item; {@code (order_id, sku)} is unique. */
@Entity
@Table(name = "order_items")
public class OrderItem {

  @Id private UUID id;

  @ManyToOne
  @JoinColumn(name = "order_id", nullable = false)
  private Order order;

  @Column(nullable = false)
  private String sku;

  @Column(nullable = false)
  private int quantity;

  @Column(name = "unit_price", nullable = false)
  private BigDecimal unitPrice;

  protected OrderItem() {}

  public OrderItem(UUID id, String sku, int quantity, BigDecimal unitPrice) {
    this.id = id;
    this.sku = sku;
    this.quantity = quantity;
    this.unitPrice = unitPrice;
  }

  public UUID getId() {
    return id;
  }

  public Order getOrder() {
    return order;
  }

  void setOrder(Order order) {
    this.order = order;
  }

  public String getSku() {
    return sku;
  }

  public int getQuantity() {
    return quantity;
  }

  public BigDecimal getUnitPrice() {
    return unitPrice;
  }
}
