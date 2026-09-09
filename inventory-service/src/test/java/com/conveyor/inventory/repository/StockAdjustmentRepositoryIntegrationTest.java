package com.conveyor.inventory.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.inventory.domain.StockAdjustment;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

class StockAdjustmentRepositoryIntegrationTest extends AbstractIntegrationTest {

  @Autowired private StockAdjustmentRepository stockAdjustmentRepository;

  @Test
  @Transactional
  void recordsAnAuditedAdjustment() {
    StockAdjustment saved =
        stockAdjustmentRepository.saveAndFlush(
            new StockAdjustment(
                UUID.randomUUID(), "SKU-ADJ-1", -5, "damaged in transit", "admin-1"));

    StockAdjustment found = stockAdjustmentRepository.findById(saved.getId()).orElseThrow();
    assertThat(found.getDelta()).isEqualTo(-5);
    assertThat(found.getActor()).isEqualTo("admin-1");
  }
}
