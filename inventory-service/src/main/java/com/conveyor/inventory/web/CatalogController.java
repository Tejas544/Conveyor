package com.conveyor.inventory.web;

import com.conveyor.inventory.catalog.CatalogRepository;
import com.conveyor.inventory.domain.SkuNotFoundException;
import com.conveyor.inventory.web.dto.CatalogResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** ARCHITECTURE.md §5.3, §10.3: Mongo-backed product catalog reads (ADR-12). */
@RestController
@RequestMapping("/api/v1/catalog")
@Tag(name = "Catalog")
public class CatalogController {

  private final CatalogRepository catalogRepository;

  public CatalogController(CatalogRepository catalogRepository) {
    this.catalogRepository = catalogRepository;
  }

  @GetMapping("/{sku}")
  @Operation(summary = "Catalog metadata for one SKU.")
  public CatalogResponse get(@PathVariable String sku) {
    return catalogRepository
        .findById(sku)
        .map(CatalogResponse::from)
        .orElseThrow(() -> new SkuNotFoundException(sku));
  }

  @GetMapping
  @Operation(summary = "Catalog search by product name; omit q for the full catalog.")
  public List<CatalogResponse> search(@RequestParam(required = false) String q) {
    var documents =
        (q == null || q.isBlank())
            ? catalogRepository.findAll()
            : catalogRepository.findByNameContainingIgnoreCase(q);
    return documents.stream().map(CatalogResponse::from).toList();
  }
}
