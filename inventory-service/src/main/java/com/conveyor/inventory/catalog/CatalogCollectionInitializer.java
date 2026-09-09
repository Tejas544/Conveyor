package com.conveyor.inventory.catalog;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.ValidationOptions;
import java.util.List;
import org.bson.Document;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * ARCHITECTURE.md §5.3. MongoDB has no Flyway-equivalent migration tool, so the {@code $jsonSchema}
 * validator on {@code catalog} is created here, once, idempotently, at startup — the Mongo analogue
 * of the Postgres {@code check} constraints in the Flyway migrations.
 */
@Component
public class CatalogCollectionInitializer implements InitializingBean {

  private static final String COLLECTION = "catalog";

  private final MongoTemplate mongoTemplate;

  public CatalogCollectionInitializer(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  @Override
  public void afterPropertiesSet() {
    if (mongoTemplate.collectionExists(COLLECTION)) {
      return;
    }

    Document schema = new Document("$jsonSchema", validatorSchema());
    MongoDatabase db = mongoTemplate.getDb();
    db.createCollection(
        COLLECTION,
        new CreateCollectionOptions().validationOptions(new ValidationOptions().validator(schema)));
  }

  private Document validatorSchema() {
    return new Document("bsonType", "object")
        .append("required", List.of("_id", "name", "active"))
        .append(
            "properties",
            new Document()
                .append("_id", new Document("bsonType", "string"))
                .append("name", new Document("bsonType", "string"))
                .append("active", new Document("bsonType", "bool")));
  }
}
