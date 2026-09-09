package com.conveyor.inventory.catalog;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * ARCHITECTURE.md §5.3. One document per SKU; {@code sku}/{@code name}/{@code active} are the
 * fields the {@code $jsonSchema} validator enforces (see {@link CatalogCollectionInitializer}).
 * Free-form {@code attributes} beneath is deliberately unvalidated — that variability is the reason
 * this lives in Mongo rather than a relational table of nullable columns.
 */
@Document(collection = "catalog")
public class CatalogDocument {

  @Id private String sku;
  private String name;
  private String description;
  private String category;
  private boolean active;
  private List<String> images;
  private Map<String, Object> attributes;
  private Instant updatedAt;

  protected CatalogDocument() {}

  public CatalogDocument(
      String sku,
      String name,
      String description,
      String category,
      boolean active,
      List<String> images,
      Map<String, Object> attributes,
      Instant updatedAt) {
    this.sku = sku;
    this.name = name;
    this.description = description;
    this.category = category;
    this.active = active;
    this.images = images;
    this.attributes = attributes;
    this.updatedAt = updatedAt;
  }

  public String getSku() {
    return sku;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public String getCategory() {
    return category;
  }

  public boolean isActive() {
    return active;
  }

  public List<String> getImages() {
    return images;
  }

  public Map<String, Object> getAttributes() {
    return attributes;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
