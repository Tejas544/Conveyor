package com.conveyor.inventory.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.inventory.domain.Reservation;
import com.conveyor.inventory.domain.ReservationStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

class ReservationRepositoryIntegrationTest extends AbstractIntegrationTest {

  @Autowired private ReservationRepository reservationRepository;

  @Test
  @Transactional
  void savesAndFindsByOrderIdAndSku() {
    UUID orderId = UUID.randomUUID();
    reservationRepository.saveAndFlush(
        new Reservation(UUID.randomUUID(), orderId, "SKU-A", 3, ReservationStatus.HELD));

    Reservation found = reservationRepository.findByOrderIdAndSku(orderId, "SKU-A").orElseThrow();
    assertThat(found.getQuantity()).isEqualTo(3);
    assertThat(found.getStatus()).isEqualTo(ReservationStatus.HELD);
  }

  @Test
  @Transactional
  void orderSkuPairIsUnique() {
    UUID orderId = UUID.randomUUID();
    reservationRepository.saveAndFlush(
        new Reservation(UUID.randomUUID(), orderId, "SKU-B", 1, ReservationStatus.HELD));

    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            reservationRepository.saveAndFlush(
                new Reservation(UUID.randomUUID(), orderId, "SKU-B", 2, ReservationStatus.HELD)));
  }

  @Test
  @Transactional
  void releaseUpdatesStatusAndTimestamp() {
    Reservation reservation =
        reservationRepository.saveAndFlush(
            new Reservation(
                UUID.randomUUID(), UUID.randomUUID(), "SKU-C", 1, ReservationStatus.HELD));

    reservation.setStatus(ReservationStatus.RELEASED);
    reservation.setReleasedAt(java.time.Instant.now());
    reservationRepository.saveAndFlush(reservation);

    Reservation reloaded = reservationRepository.findById(reservation.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    assertThat(reloaded.getReleasedAt()).isNotNull();
  }
}
