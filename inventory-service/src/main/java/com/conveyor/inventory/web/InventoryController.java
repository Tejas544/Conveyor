package com.conveyor.inventory.web;

import com.conveyor.inventory.catalog.CatalogRepository;
import com.conveyor.inventory.domain.StockItem;
import com.conveyor.inventory.service.InventoryService;
import com.conveyor.inventory.web.dto.AdjustStockRequest;
import com.conveyor.inventory.web.dto.InventoryDetailResponse;
import com.conveyor.inventory.web.dto.ReservationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** ARCHITECTURE.md §10.3. */
@RestController
@RequestMapping("/api/v1/inventory")
@Tag(name = "Inventory")
public class InventoryController {

  private final InventoryService inventoryService;
  private final CatalogRepository catalogRepository;

  public InventoryController(
      InventoryService inventoryService, CatalogRepository catalogRepository) {
    this.inventoryService = inventoryService;
    this.catalogRepository = catalogRepository;
  }

  @GetMapping
  @Operation(
      summary =
          "Stock levels, optionally filtered to low-stock SKUs, joined with catalog metadata.")
  public Page<InventoryDetailResponse> list(
      @RequestParam(defaultValue = "false") boolean lowStock, Pageable pageable) {
    Page<StockItem> page = inventoryService.search(lowStock, pageable);
    return page.map(
        item ->
            InventoryDetailResponse.from(
                item, catalogRepository.findById(item.getSku()).orElse(null)));
  }

  @GetMapping("/{sku}")
  @Operation(summary = "Stock level for one SKU, joined with catalog metadata.")
  public InventoryDetailResponse get(@PathVariable String sku) {
    StockItem stockItem = inventoryService.getStockItem(sku);
    return InventoryDetailResponse.from(stockItem, catalogRepository.findById(sku).orElse(null));
  }

  @PostMapping("/{sku}/adjust")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Admin stock correction — audited to stock_adjustments. ADMIN only.")
  public InventoryDetailResponse adjust(
      @PathVariable String sku,
      @Valid @RequestBody AdjustStockRequest request,
      Principal principal) {
    String actor = principal != null ? principal.getName() : "unknown";
    StockItem stockItem = inventoryService.adjust(sku, request.delta(), request.reason(), actor);
    return InventoryDetailResponse.from(stockItem, catalogRepository.findById(sku).orElse(null));
  }

  @GetMapping("/{sku}/reservations")
  @Operation(summary = "Who is currently holding this SKU's stock.")
  public List<ReservationResponse> reservations(@PathVariable String sku) {
    return inventoryService.getReservations(sku).stream().map(ReservationResponse::from).toList();
  }
}
