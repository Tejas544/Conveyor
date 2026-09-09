package com.conveyor.inventory.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.mongodb.MongoWriteException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * PLAN.md Phase 2: the Mongo {@code $jsonSchema} validator rejects a document missing a required
 * field (ARCHITECTURE.md §5.3 — {@code sku}/{@code name}/{@code active} are enforced).
 */
class CatalogValidatorIntegrationTest extends AbstractIntegrationTest {

  @Autowired private CatalogRepository catalogRepository;
  @Autowired private MongoTemplate mongoTemplate;

  @Test
  void acceptsAValidDocument() {
    CatalogDocument saved =
        catalogRepository.save(
            new CatalogDocument(
                "SKU-CAT-1",
                "Widget",
                "A widget",
                "widgets",
                true,
                List.of("https://example.test/w.png"),
                Map.of("color", "blue"),
                Instant.now()));

    assertThat(catalogRepository.findById(saved.getSku())).isPresent();
  }

  @Test
  void rejectsADocumentMissingARequiredField() {
    // Inserted directly via the driver, bypassing CatalogDocument, so a document that is missing
    // "name" (required by the validator) can be constructed at all.
    Document invalid = new Document("_id", "SKU-CAT-INVALID").append("active", true);

    assertThatThrownBy(() -> mongoTemplate.getCollection("catalog").insertOne(invalid))
        .isInstanceOf(MongoWriteException.class)
        .hasMessageContaining("Document failed validation");
  }
}
