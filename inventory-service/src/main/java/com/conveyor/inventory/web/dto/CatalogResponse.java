package com.conveyor.inventory.web.dto;

import com.conveyor.inventory.catalog.CatalogDocument;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** ARCHITECTURE.md §10.3: {@code GET /catalog/{sku}} / {@code GET /catalog?q=}. */
public record CatalogResponse(
    String sku,
    String name,
    String description,
    String category,
    boolean active,
    List<String> images,
    Map<String, Object> attributes,
    Instant updatedAt) {

  public static CatalogResponse from(CatalogDocument document) {
    return new CatalogResponse(
        document.getSku(),
        document.getName(),
        document.getDescription(),
        document.getCategory(),
        document.isActive(),
        document.getImages(),
        document.getAttributes(),
        document.getUpdatedAt());
  }
}
