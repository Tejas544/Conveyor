package com.conveyor.inventory.web.dto;

import com.conveyor.inventory.catalog.CatalogDocument;
import com.conveyor.inventory.domain.StockItem;

/** ARCHITECTURE.md §10.3: {@code {sku, onHand, reserved, available, reorderLevel, catalog:{…}}}. */
public record InventoryDetailResponse(
    String sku,
    int onHand,
    int reserved,
    int available,
    int reorderLevel,
    CatalogSummaryResponse catalog) {

  public static InventoryDetailResponse from(StockItem stockItem, CatalogDocument catalog) {
    return new InventoryDetailResponse(
        stockItem.getSku(),
        stockItem.getOnHand(),
        stockItem.getReserved(),
        stockItem.getAvailable(),
        stockItem.getReorderLevel(),
        catalog == null ? null : CatalogSummaryResponse.from(catalog));
  }
}
