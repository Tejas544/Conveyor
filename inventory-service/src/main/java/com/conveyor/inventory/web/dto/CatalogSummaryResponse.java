package com.conveyor.inventory.web.dto;

import com.conveyor.inventory.catalog.CatalogDocument;

/** ARCHITECTURE.md §10.3: the catalog fragment embedded in {@link InventoryDetailResponse}. */
public record CatalogSummaryResponse(String name, String category, boolean active) {

  public static CatalogSummaryResponse from(CatalogDocument document) {
    return new CatalogSummaryResponse(
        document.getName(), document.getCategory(), document.isActive());
  }
}
