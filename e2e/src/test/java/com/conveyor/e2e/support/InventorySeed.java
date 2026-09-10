package com.conveyor.e2e.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Seeds one SKU directly into inventory-service's Postgres database. {@code POST
 * /inventory/{sku}/adjust} (the only write path the public API exposes for stock) requires the SKU
 * to already exist and is {@code ADMIN}-gated by a JWT issuer PLAN.md defers to Phase 8 — so, same
 * as a human running {@code make seed} before placing a demo order, this module seeds the one
 * prerequisite row the black-box order flow needs directly against the database, then never touches
 * that connection again.
 */
public final class InventorySeed {

  private InventorySeed() {}

  public static void seedStock(
      String jdbcUrl, String username, String password, String sku, int onHand)
      throws SQLException {
    try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
        var statement =
            connection.prepareStatement(
                "insert into stock_items (sku, on_hand, reserved, reorder_level) "
                    + "values (?, ?, 0, 5) on conflict (sku) do update set on_hand = excluded.on_hand")) {
      statement.setString(1, sku);
      statement.setInt(2, onHand);
      statement.executeUpdate();
    }
  }
}
