package com.conveyor.dispatch.repository;

import com.conveyor.dispatch.domain.Shipment;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipmentRepository extends JpaRepository<Shipment, UUID> {

  Optional<Shipment> findByOrderId(UUID orderId);
}
