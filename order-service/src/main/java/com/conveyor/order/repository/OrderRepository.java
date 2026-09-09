package com.conveyor.order.repository;

import com.conveyor.order.domain.Order;
import com.conveyor.order.domain.OrderStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, UUID> {

  Optional<Order> findByIdempotencyKey(String idempotencyKey);

  List<Order> findByStatus(OrderStatus status);
}
