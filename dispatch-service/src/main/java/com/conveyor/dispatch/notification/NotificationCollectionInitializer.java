package com.conveyor.dispatch.notification;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.ValidationOptions;
import java.util.List;
import org.bson.Document;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * ARCHITECTURE.md §5.5. MongoDB has no Flyway-equivalent migration tool, so the {@code $jsonSchema}
 * validator on {@code notifications} is created here, once, idempotently, at startup.
 */
@Component
public class NotificationCollectionInitializer implements InitializingBean {

  private static final String COLLECTION = "notifications";

  private final MongoTemplate mongoTemplate;

  public NotificationCollectionInitializer(MongoTemplate mongoTemplate) {
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
        .append("required", List.of("orderId", "channel", "status"))
        .append(
            "properties",
            new Document()
                .append("orderId", new Document("bsonType", "string"))
                .append(
                    "channel",
                    new Document("bsonType", "string").append("enum", List.of("EMAIL", "SMS")))
                .append("status", new Document("bsonType", "string")));
  }
}
