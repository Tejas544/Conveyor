package com.conveyor.dispatch.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.mongodb.MongoWriteException;
import java.time.Instant;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * PLAN.md Phase 2: the Mongo {@code $jsonSchema} validator rejects a document missing a required
 * field (ARCHITECTURE.md §5.5 — {@code orderId}/{@code channel}/{@code status} are enforced).
 */
class NotificationValidatorIntegrationTest extends AbstractIntegrationTest {

  @Autowired private NotificationRepository notificationRepository;
  @Autowired private MongoTemplate mongoTemplate;

  @Test
  void acceptsAValidDocument() {
    NotificationDocument saved =
        notificationRepository.save(
            new NotificationDocument(
                UUID.randomUUID().toString(),
                "EMAIL",
                "order-confirmed",
                "customer@example.test",
                "Your order shipped",
                "SENT",
                Instant.now()));

    assertThat(notificationRepository.findById(saved.getId())).isPresent();
  }

  @Test
  void rejectsADocumentMissingARequiredField() {
    // Missing "status", required by the validator; inserted directly via the driver so a document
    // that skips it can be constructed at all.
    Document invalid =
        new Document("orderId", UUID.randomUUID().toString()).append("channel", "EMAIL");

    assertThatThrownBy(() -> mongoTemplate.getCollection("notifications").insertOne(invalid))
        .isInstanceOf(MongoWriteException.class)
        .hasMessageContaining("Document failed validation");
  }
}
