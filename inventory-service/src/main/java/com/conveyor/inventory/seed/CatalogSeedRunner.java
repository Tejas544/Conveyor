package com.conveyor.inventory.seed;

import com.conveyor.inventory.catalog.CatalogDocument;
import com.conveyor.inventory.catalog.CatalogRepository;
import com.conveyor.inventory.domain.StockItem;
import com.conveyor.inventory.repository.StockItemRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@code make seed} (PLAN.md Phase 2): ~50 catalog SKUs with stock, in both {@code stock_items}
 * (Postgres) and {@code catalog} (Mongo) — the same SKU set in both datastores, since Inventory
 * Service owns both halves of the polyglot split (ADR-12). Idempotent — a SKU already present in
 * {@code stock_items} is left alone. Runs under {@code SPRING_PROFILES_ACTIVE=seed} only, via
 * {@code docker compose run --rm}, and exits the JVM once done.
 */
@Component
@Profile("seed")
public class CatalogSeedRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(CatalogSeedRunner.class);
  private static final int SKU_COUNT = 50;
  private static final List<String> CATEGORIES =
      List.of("electronics", "home", "outdoors", "office", "toys");

  private final StockItemRepository stockItemRepository;
  private final CatalogRepository catalogRepository;
  private final ConfigurableApplicationContext context;

  public CatalogSeedRunner(
      StockItemRepository stockItemRepository,
      CatalogRepository catalogRepository,
      ConfigurableApplicationContext context) {
    this.stockItemRepository = stockItemRepository;
    this.catalogRepository = catalogRepository;
    this.context = context;
  }

  @Override
  public void run(ApplicationArguments args) {
    int seeded = 0;
    for (int i = 1; i <= SKU_COUNT; i++) {
      String sku = "SKU-%04d".formatted(i);
      if (stockItemRepository.existsById(sku)) {
        continue;
      }
      String category = CATEGORIES.get(i % CATEGORIES.size());
      int onHand = 20 + (i % 10) * 10;

      stockItemRepository.save(new StockItem(sku, onHand, 0, 10));
      catalogRepository.save(
          new CatalogDocument(
              sku,
              "Demo product %d".formatted(i),
              "Seeded demo product for the %s category.".formatted(category),
              category,
              true,
              List.of(),
              Map.of("seedIndex", i),
              Instant.now()));
      seeded++;
    }
    log.info("Catalog seed complete: {} new SKUs (of {} total).", seeded, SKU_COUNT);
    System.exit(SpringApplication.exit(context, () -> 0));
  }
}
