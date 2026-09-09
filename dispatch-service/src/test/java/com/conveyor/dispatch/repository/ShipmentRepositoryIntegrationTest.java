package com.conveyor.dispatch.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.dispatch.domain.Shipment;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

class ShipmentRepositoryIntegrationTest extends AbstractIntegrationTest {

  @Autowired private ShipmentRepository shipmentRepository;

  @Test
  @Transactional
  void savesAndFindsByOrderId() {
    UUID orderId = UUID.randomUUID();
    shipmentRepository.saveAndFlush(
        new Shipment(UUID.randomUUID(), orderId, "UPS", "1Z999AA10123456784", "CREATED"));

    assertThat(shipmentRepository.findByOrderId(orderId)).isPresent();
  }

  @Test
  @Transactional
  void orderIdIsUnique() {
    UUID orderId = UUID.randomUUID();
    shipmentRepository.saveAndFlush(
        new Shipment(UUID.randomUUID(), orderId, "UPS", "TRACK-A", "CREATED"));

    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            shipmentRepository.saveAndFlush(
                new Shipment(UUID.randomUUID(), orderId, "FedEx", "TRACK-B", "CREATED")));
  }
}
