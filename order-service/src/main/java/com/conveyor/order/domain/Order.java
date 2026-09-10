package com.conveyor.order.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/** ARCHITECTURE.md §5.1. The order aggregate root; {@link #status} is the state-machine value. */
@Entity
@Table(name = "orders")
public class Order {

  @Id private UUID id;

  @Column(name = "customer_id", nullable = false)
  private UUID customerId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private OrderStatus status;

  @Column(name = "saga_id")
  private UUID sagaId;

  @Column(name = "total_amount", nullable = false)
  private BigDecimal totalAmount;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(nullable = false, length = 3)
  private String currency;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "shipping_address", nullable = false)
  private Map<String, Object> shippingAddress;

  @Column(name = "idempotency_key")
  private String idempotencyKey;

  @Column(name = "payment_method_token")
  private String paymentMethodToken;

  @Version private long version;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  // EAGER: Order and its line items are always read together (OrderDetailResponse, the
  // OrderPlaced payload) — never worth a second query or a LazyInitializationException risk.
  @OneToMany(
      mappedBy = "order",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.EAGER)
  @OrderBy("sku")
  private List<OrderItem> items = new ArrayList<>();

  protected Order() {}

  public Order(
      UUID id,
      UUID customerId,
      OrderStatus status,
      BigDecimal totalAmount,
      String currency,
      Map<String, Object> shippingAddress,
      String idempotencyKey) {
    this(id, customerId, status, totalAmount, currency, shippingAddress, idempotencyKey, null);
  }

  public Order(
      UUID id,
      UUID customerId,
      OrderStatus status,
      BigDecimal totalAmount,
      String currency,
      Map<String, Object> shippingAddress,
      String idempotencyKey,
      String paymentMethodToken) {
    this.id = id;
    this.customerId = customerId;
    this.status = status;
    this.totalAmount = totalAmount;
    this.currency = currency;
    this.shippingAddress = shippingAddress;
    this.idempotencyKey = idempotencyKey;
    this.paymentMethodToken = paymentMethodToken;
  }

  public UUID getId() {
    return id;
  }

  public UUID getCustomerId() {
    return customerId;
  }

  public OrderStatus getStatus() {
    return status;
  }

  /**
   * The only way {@link #status} changes after construction. ARCHITECTURE.md §7.1: an illegal
   * transition throws rather than silently writing.
   */
  public void transitionTo(OrderStatus target) {
    if (!status.canTransitionTo(target)) {
      throw new IllegalOrderTransitionException(status, target);
    }
    this.status = target;
  }

  public UUID getSagaId() {
    return sagaId;
  }

  public void setSagaId(UUID sagaId) {
    this.sagaId = sagaId;
  }

  public BigDecimal getTotalAmount() {
    return totalAmount;
  }

  public String getCurrency() {
    return currency;
  }

  public Map<String, Object> getShippingAddress() {
    return shippingAddress;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public String getPaymentMethodToken() {
    return paymentMethodToken;
  }

  public long getVersion() {
    return version;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public List<OrderItem> getItems() {
    return items;
  }

  public void addItem(OrderItem item) {
    items.add(item);
    item.setOrder(this);
  }
}
