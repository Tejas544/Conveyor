package com.conveyor.order.repository;

import com.conveyor.order.domain.Order;
import com.conveyor.order.domain.OrderStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, UUID> {

  Optional<Order> findByIdempotencyKey(String idempotencyKey);

  List<Order> findByStatus(OrderStatus status);

  // Postgres can't infer a bind parameter's type from "? is null" alone (no other type context
  // for it in that branch); casting makes the type explicit so it stops rejecting the query with
  // "could not determine data type of parameter".
  @Query(
      "select o from Order o where (:status is null or o.status = :status) "
          + "and (cast(:from as timestamp) is null or o.createdAt >= :from) "
          + "and (cast(:to as timestamp) is null or o.createdAt <= :to)")
  Page<Order> search(
      @Param("status") OrderStatus status,
      @Param("from") Instant from,
      @Param("to") Instant to,
      Pageable pageable);

  @Query("select o.status, count(o) from Order o group by o.status")
  List<Object[]> countByStatusGrouped();
}
