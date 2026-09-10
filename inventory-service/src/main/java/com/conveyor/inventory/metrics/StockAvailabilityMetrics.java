package com.conveyor.inventory.metrics;

import com.conveyor.inventory.repository.StockItemRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ARCHITECTURE.md §11's {@code conveyor_stock_available{sku}} gauge — low-stock alerting. The set
 * of SKUs changes over time (new catalog entries), so this uses {@link MultiGauge} rather than one
 * static {@code Gauge} per SKU, refreshing on a schedule rather than per-request since it is
 * consumed by Prometheus's own poll, not a live request path.
 */
@Component
public class StockAvailabilityMetrics {

  private final StockItemRepository repository;
  private final MultiGauge stockAvailable;

  public StockAvailabilityMetrics(StockItemRepository repository, MeterRegistry registry) {
    this.repository = repository;
    this.stockAvailable = MultiGauge.builder("conveyor_stock_available").register(registry);
    refresh();
  }

  @Scheduled(fixedDelayString = "${conveyor.metrics.stock-refresh-interval-ms:5000}")
  public void refresh() {
    List<MultiGauge.Row<?>> rows =
        repository.findAll().stream()
            .<MultiGauge.Row<?>>map(
                item -> MultiGauge.Row.of(Tags.of("sku", item.getSku()), item.getAvailable()))
            .toList();
    stockAvailable.register(rows, true);
  }
}
