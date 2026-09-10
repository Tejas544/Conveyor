package com.conveyor.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.inventory.outbox.OutboxRecordRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * PLAN.md Phase 4: {@code ReleaseInventory} for an unknown reservation is a no-op success, not an
 * error — compensations must be safe to replay even against state that was never created (e.g. the
 * matching {@code ReserveInventory} never actually succeeded).
 */
class ReleaseUnknownReservationIntegrationTest extends AbstractIntegrationTest {

  @Autowired private InventoryReservationService reservationService;
  @Autowired private OutboxRecordRepository outboxRecordRepository;

  @Test
  void releasingAnUnknownReservationIdSucceedsWithoutError() {
    UUID orderId = UUID.randomUUID();
    UUID unknownReservationId = UUID.randomUUID();

    assertThatCode(
            () ->
                reservationService.handleReleaseInventory(
                    UUID.randomUUID(), orderId, null, null, List.of(unknownReservationId)))
        .doesNotThrowAnyException();

    boolean replyPublished =
        outboxRecordRepository.findAll().stream()
            .anyMatch(
                r ->
                    r.getAggregateId().equals(orderId.toString())
                        && r.getEventType().equals("InventoryReleased"));
    assertThat(replyPublished).isTrue();
  }
}
