package com.conveyor.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.contracts.events.InventoryItemPayload;
import com.conveyor.inventory.domain.StockItem;
import com.conveyor.inventory.outbox.OutboxRecord;
import com.conveyor.inventory.outbox.OutboxRecordRepository;
import com.conveyor.inventory.repository.ReservationRepository;
import com.conveyor.inventory.repository.StockItemRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * PLAN.md Phase 4: a multi-SKU order where one SKU is short reserves <strong>nothing</strong> — the
 * plentiful SKU's guarded UPDATE is explicitly undone the moment the short SKU's UPDATE returns
 * zero rows, never left half-applied.
 */
class MultiSkuPartialReservationIntegrationTest extends AbstractIntegrationTest {

  @Autowired private InventoryReservationService reservationService;
  @Autowired private ReservationRepository reservationRepository;
  @Autowired private StockItemRepository stockItemRepository;
  @Autowired private OutboxRecordRepository outboxRecordRepository;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void oneShortSkuInAMultiSkuOrderReservesNoneOfIt() {
    stockItemRepository.saveAndFlush(new StockItem("SKU-PLENTY-1", 100, 0, 2));
    stockItemRepository.saveAndFlush(new StockItem("SKU-SHORT-1", 2, 0, 2));
    UUID orderId = UUID.randomUUID();

    reservationService.handleReserveInventory(
        UUID.randomUUID(),
        orderId,
        null,
        null,
        List.of(
            new InventoryItemPayload("SKU-PLENTY-1", 5),
            new InventoryItemPayload("SKU-SHORT-1", 5)));

    assertThat(stockItemRepository.findById("SKU-PLENTY-1").orElseThrow().getReserved()).isZero();
    assertThat(stockItemRepository.findById("SKU-SHORT-1").orElseThrow().getReserved()).isZero();
    assertThat(reservationRepository.findByOrderId(orderId)).isEmpty();

    OutboxRecord reply =
        outboxRecordRepository.findAll().stream()
            .filter(r -> r.getAggregateId().equals(orderId.toString()))
            .findFirst()
            .orElseThrow();
    assertThat(reply.getEventType()).isEqualTo("InventoryReservationFailed");

    // objectMapper here is the same Spring-managed ObjectMapper bean Hibernate's JSON column
    // mapping now reuses (JacksonHibernateJsonFormatConfiguration in conveyor-common — BUGS.md
    // BUG-0010) rather than an internally-constructed one that happened to pick up jackson-module-
    // scala from the test classpath, so nested payload content is consistently java.util.Map.
    JsonNode payload = objectMapper.valueToTree(reply.getPayload()).get("payload");
    assertThat(payload.get("reason").asText()).isEqualTo("INSUFFICIENT_STOCK");
    JsonNode shortfalls = payload.get("shortfalls");
    assertThat(shortfalls).hasSize(1);
    assertThat(shortfalls.get(0).get("sku").asText()).isEqualTo("SKU-SHORT-1");
  }
}
