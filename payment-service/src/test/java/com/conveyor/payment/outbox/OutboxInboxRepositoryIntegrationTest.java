package com.conveyor.payment.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.conveyor.common.testsupport.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

class OutboxInboxRepositoryIntegrationTest extends AbstractIntegrationTest {

  @Autowired private OutboxRecordRepository outboxRecordRepository;
  @Autowired private InboxRecordRepository inboxRecordRepository;
  @Autowired private EntityManager entityManager;

  @Test
  @Transactional
  void outboxRecordIsWrittenUnpublishedThenMarkedPublished() {
    OutboxRecord record =
        new OutboxRecord(
            UUID.randomUUID(),
            "Payment",
            UUID.randomUUID().toString(),
            "PaymentCharged",
            "conveyor.saga.replies.v1",
            UUID.randomUUID().toString(),
            Map.of("paymentId", UUID.randomUUID().toString()),
            Map.of("content-type", "application/json"));
    OutboxRecord saved = outboxRecordRepository.saveAndFlush(record);
    assertThat(saved.getPublishedAt()).isNull();

    saved.markPublished(Instant.now());
    outboxRecordRepository.saveAndFlush(saved);

    assertThat(outboxRecordRepository.findById(saved.getId()).orElseThrow().getPublishedAt())
        .isNotNull();
  }

  @Test
  @Transactional
  void inboxDedupIsAPrimaryKeyViolation() {
    InboxRecordId id = new InboxRecordId(UUID.randomUUID(), "payment-service");
    inboxRecordRepository.saveAndFlush(new InboxRecord(id));

    // entityManager.persist(), not repository.save(): InboxRecord has a manually-assigned
    // (non-generated) @EmbeddedId, so Spring Data's save() always calls merge() — an upsert that
    // would silently update the existing row instead of exercising the primary-key violation this
    // test is for.
    assertThrows(
        PersistenceException.class,
        () -> {
          entityManager.persist(new InboxRecord(id));
          entityManager.flush();
        });
  }
}
