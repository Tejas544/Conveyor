package com.conveyor.order.outbox;

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
            "Order",
            UUID.randomUUID().toString(),
            "OrderPlaced",
            "conveyor.order.events.v1",
            UUID.randomUUID().toString(),
            Map.of("customerId", UUID.randomUUID().toString()),
            Map.of("content-type", "application/json"));
    OutboxRecord saved = outboxRecordRepository.saveAndFlush(record);
    assertThat(saved.getPublishedAt()).isNull();
    assertThat(saved.getAttempts()).isZero();

    saved.markPublished(Instant.now());
    saved.incrementAttempts();
    outboxRecordRepository.saveAndFlush(saved);

    OutboxRecord reloaded = outboxRecordRepository.findById(saved.getId()).orElseThrow();
    assertThat(reloaded.getPublishedAt()).isNotNull();
    assertThat(reloaded.getAttempts()).isEqualTo(1);
  }

  @Test
  @Transactional
  void inboxDedupIsAPrimaryKeyViolation() {
    InboxRecordId id = new InboxRecordId(UUID.randomUUID(), "order-service");
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

  @Test
  @Transactional
  void sameMessageDifferentConsumerIsNotADuplicate() {
    UUID messageId = UUID.randomUUID();
    inboxRecordRepository.saveAndFlush(new InboxRecord(new InboxRecordId(messageId, "consumer-a")));
    inboxRecordRepository.saveAndFlush(new InboxRecord(new InboxRecordId(messageId, "consumer-b")));

    assertThat(inboxRecordRepository.findAll()).hasSize(2);
  }
}
