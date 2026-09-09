package com.conveyor.inventory.repository;

import com.conveyor.inventory.domain.Reservation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

  Optional<Reservation> findByOrderIdAndSku(UUID orderId, String sku);

  List<Reservation> findByOrderId(UUID orderId);

  List<Reservation> findBySku(String sku);
}
