package com.conveyor.inventory.repository;

import com.conveyor.inventory.domain.StockAdjustment;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockAdjustmentRepository extends JpaRepository<StockAdjustment, UUID> {}
