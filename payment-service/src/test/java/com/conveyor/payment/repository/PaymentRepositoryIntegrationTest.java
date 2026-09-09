package com.conveyor.payment.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.payment.domain.Payment;
import com.conveyor.payment.domain.PaymentStatus;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

class PaymentRepositoryIntegrationTest extends AbstractIntegrationTest {

  @Autowired private PaymentRepository paymentRepository;

  @Test
  @Transactional
  void savesAndFindsByOrderId() {
    UUID orderId = UUID.randomUUID();
    paymentRepository.saveAndFlush(
        new Payment(
            UUID.randomUUID(),
            orderId,
            new BigDecimal("19.99"),
            "USD",
            PaymentStatus.CAPTURED,
            "gw-ref-1"));

    Payment found = paymentRepository.findByOrderId(orderId).orElseThrow();
    assertThat(found.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
  }

  @Test
  @Transactional
  void orderIdIsUnique() {
    UUID orderId = UUID.randomUUID();
    paymentRepository.saveAndFlush(
        new Payment(
            UUID.randomUUID(),
            orderId,
            new BigDecimal("10.00"),
            "USD",
            PaymentStatus.CAPTURED,
            "gw-a"));

    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            paymentRepository.saveAndFlush(
                new Payment(
                    UUID.randomUUID(),
                    orderId,
                    new BigDecimal("10.00"),
                    "USD",
                    PaymentStatus.CAPTURED,
                    "gw-b")));
  }

  @Test
  @Transactional
  void updatesStatusToRefunded() {
    Payment saved =
        paymentRepository.saveAndFlush(
            new Payment(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("5.00"),
                "USD",
                PaymentStatus.CAPTURED,
                "gw-c"));

    saved.setStatus(PaymentStatus.REFUNDED);
    paymentRepository.saveAndFlush(saved);

    assertThat(paymentRepository.findById(saved.getId()).orElseThrow().getStatus())
        .isEqualTo(PaymentStatus.REFUNDED);
  }
}
