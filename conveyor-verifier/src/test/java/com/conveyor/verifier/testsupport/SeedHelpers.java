package com.conveyor.verifier.testsupport;

import com.conveyor.verifier.config.ServiceDatabases;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Row-insert helpers shared by the seeded-violation and precision tests. */
public final class SeedHelpers {

  private SeedHelpers() {}

  public static void insertOrder(
      ServiceDatabases db, UUID id, String status, UUID sagaId, BigDecimal totalAmount) {
    db.get("order")
        .update(
            "insert into orders (id, customer_id, status, saga_id, total_amount, currency, "
                + "shipping_address) values (?, ?, ?, ?, ?, 'USD', '{}'::jsonb)",
            id,
            UUID.randomUUID(),
            status,
            sagaId,
            totalAmount);
  }

  public static void insertOrderItem(
      ServiceDatabases db, UUID orderId, String sku, int quantity, BigDecimal unitPrice) {
    db.get("order")
        .update(
            "insert into order_items (id, order_id, sku, quantity, unit_price) values (?, ?, ?, ?, ?)",
            UUID.randomUUID(),
            orderId,
            sku,
            quantity,
            unitPrice);
  }

  public static void insertStockItem(ServiceDatabases db, String sku, int onHand, int reserved) {
    db.get("inventory")
        .update(
            "insert into stock_items (sku, on_hand, reserved, reorder_level) values (?, ?, ?, 10)",
            sku,
            onHand,
            reserved);
  }

  public static void insertStockAdjustment(ServiceDatabases db, String sku, int delta) {
    db.get("inventory")
        .update(
            "insert into stock_adjustments (id, sku, delta, reason, actor) values (?, ?, ?, 'SEED', 'test')",
            UUID.randomUUID(),
            sku,
            delta);
  }

  public static void insertReservation(
      ServiceDatabases db, UUID id, UUID orderId, String sku, int quantity, String status) {
    db.get("inventory")
        .update(
            "insert into reservations (id, order_id, sku, quantity, status) values (?, ?, ?, ?, ?)",
            id,
            orderId,
            sku,
            quantity,
            status);
  }

  public static void insertPayment(
      ServiceDatabases db, UUID id, UUID orderId, BigDecimal amount, String status) {
    db.get("payment")
        .update(
            "insert into payments (id, order_id, amount, currency, status) values (?, ?, ?, 'USD', ?)",
            id,
            orderId,
            amount,
            status);
  }

  public static void insertSaga(
      ServiceDatabases db,
      UUID id,
      UUID orderId,
      String state,
      Timestamp deadlineAt,
      Timestamp createdAt,
      Timestamp updatedAt) {
    db.get("saga")
        .update(
            "insert into saga_instances (id, order_id, definition, state, deadline_at, created_at, updated_at) "
                + "values (?, ?, 'ORDER_FULFILLMENT', ?, ?, ?, ?)",
            id,
            orderId,
            state,
            deadlineAt,
            createdAt,
            updatedAt);
  }

  public static void insertSagaStep(
      ServiceDatabases db, UUID sagaId, int seq, String step, String direction, String status) {
    db.get("saga")
        .update(
            "insert into saga_steps (saga_id, seq, step, direction, status) values (?, ?, ?, ?, ?)",
            sagaId,
            seq,
            step,
            direction,
            status);
  }

  public static void insertShipment(ServiceDatabases db, UUID id, UUID orderId, String status) {
    db.get("dispatch")
        .update(
            "insert into shipments (id, order_id, carrier, tracking_number, status) "
                + "values (?, ?, 'DEMO_CARRIER', 'TRACK-1', ?)",
            id,
            orderId,
            status);
  }

  public static void insertOutboxRow(
      ServiceDatabases db, String service, UUID id, Timestamp createdAt, Timestamp publishedAt) {
    db.get(service)
        .update(
            "insert into outbox (id, aggregate_type, aggregate_id, event_type, topic, message_key, "
                + "payload, created_at, published_at) "
                + "values (?, 'Test', ?, 'Test', 'conveyor.test.v1', ?, '{}'::jsonb, ?, ?)",
            id,
            id.toString(),
            id.toString(),
            createdAt,
            publishedAt);
  }

  public static void dropConstraints(
      ServiceDatabases db, String service, String table, char contype) {
    JdbcTemplate jdbc = db.get(service);
    List<String> names =
        jdbc.queryForList(
            "select conname from pg_constraint where conrelid = ?::regclass and contype = ?",
            String.class,
            table,
            String.valueOf(contype));
    for (String name : names) {
      jdbc.execute("alter table " + table + " drop constraint " + name);
    }
  }
}
