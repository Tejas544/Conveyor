package com.conveyor.order.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.order.domain.Order;
import com.conveyor.order.domain.OrderItem;
import com.conveyor.order.domain.OrderStatus;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/** PLAN.md Phase 2: repository CRUD coverage for the order aggregate. */
class OrderRepositoryIntegrationTest extends AbstractIntegrationTest {

  @Autowired private OrderRepository orderRepository;
  @Autowired private EntityManager entityManager;

  private Order newOrder(String idempotencyKey) {
    Order order =
        new Order(
            UUID.randomUUID(),
            UUID.randomUUID(),
            OrderStatus.PLACED,
            new BigDecimal("49.98"),
            "USD",
            Map.of(
                "line1", "1 Test St", "city", "Testville", "postalCode", "00000", "country", "IN"),
            idempotencyKey);
    order.addItem(new OrderItem(UUID.randomUUID(), "SKU-1", 2, new BigDecimal("24.99")));
    return order;
  }

  @Test
  @Transactional
  void savesAndReadsBackWithItems() {
    Order saved = orderRepository.saveAndFlush(newOrder("idem-1"));
    entityManager.clear();

    Order found = orderRepository.findById(saved.getId()).orElseThrow();
    assertThat(found.getStatus()).isEqualTo(OrderStatus.PLACED);
    assertThat(found.getItems()).hasSize(1);
    assertThat(found.getItems().get(0).getSku()).isEqualTo("SKU-1");
    assertThat(found.getShippingAddress()).containsEntry("city", "Testville");
  }

  @Test
  @Transactional
  void findsByIdempotencyKey() {
    orderRepository.saveAndFlush(newOrder("idem-unique-2"));

    assertThat(orderRepository.findByIdempotencyKey("idem-unique-2")).isPresent();
    assertThat(orderRepository.findByIdempotencyKey("does-not-exist")).isEmpty();
  }

  @Test
  @Transactional
  void idempotencyKeyIsUnique() {
    orderRepository.saveAndFlush(newOrder("idem-dup"));

    assertThrows(
        DataIntegrityViolationException.class,
        () -> orderRepository.saveAndFlush(newOrder("idem-dup")));
  }

  @Test
  @Transactional
  void findsByStatus() {
    orderRepository.saveAndFlush(newOrder("idem-status-1"));

    assertThat(orderRepository.findByStatus(OrderStatus.PLACED)).isNotEmpty();
    assertThat(orderRepository.findByStatus(OrderStatus.CONFIRMED)).isEmpty();
  }

  @Test
  @Transactional
  void updatesAndDeletes() {
    Order saved = orderRepository.saveAndFlush(newOrder("idem-upd"));
    saved.setStatus(OrderStatus.CONFIRMED);
    orderRepository.saveAndFlush(saved);
    entityManager.clear();

    assertThat(orderRepository.findById(saved.getId()).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.CONFIRMED);

    orderRepository.deleteById(saved.getId());
    orderRepository.flush();
    assertThat(orderRepository.findById(saved.getId())).isEmpty();
  }
}
