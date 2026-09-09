package com.conveyor.payment.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.payment.domain.PaymentAttempt;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * PLAN.md Phase 2 / ARCHITECTURE.md §5.4: {@code idempotency_key} uniqueness is what Phase 5's
 * no-double-charge guarantee is built on — proven here at the schema level.
 */
class PaymentAttemptRepositoryIntegrationTest extends AbstractIntegrationTest {

  @Autowired private PaymentAttemptRepository paymentAttemptRepository;

  @Test
  @Transactional
  void savesAndFindsByIdempotencyKey() {
    UUID sagaId = UUID.randomUUID();
    paymentAttemptRepository.saveAndFlush(
        new PaymentAttempt(
            UUID.randomUUID(), UUID.randomUUID(), sagaId + ":CHARGE_PAYMENT", "CAPTURED", "gw-1"));

    assertThat(paymentAttemptRepository.findByIdempotencyKey(sagaId + ":CHARGE_PAYMENT"))
        .isPresent();
  }

  @Test
  @Transactional
  void idempotencyKeyIsUnique() {
    String key = UUID.randomUUID() + ":CHARGE_PAYMENT";
    paymentAttemptRepository.saveAndFlush(
        new PaymentAttempt(UUID.randomUUID(), UUID.randomUUID(), key, "CAPTURED", "gw-1"));

    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            paymentAttemptRepository.saveAndFlush(
                new PaymentAttempt(UUID.randomUUID(), UUID.randomUUID(), key, "CAPTURED", "gw-2")));
  }
}
