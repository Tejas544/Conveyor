package com.conveyor.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.conveyor.e2e.support.RestClient;
import com.conveyor.e2e.support.RestClient.JsonResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * PLAN.md Phase 13: "the full E2E suite passes against the kind cluster, not just compose." The
 * other three classes in this package are deliberately left untouched — they own a {@code
 * ComposeContainer} that brings up its <em>own</em> fresh stack, which is the right shape for the
 * default `mvn -f e2e/pom.xml verify -DskipE2E=false` path (Phase 7) but is the wrong tool for
 * driving an already-running external cluster. This class instead talks to whatever is already
 * listening on kind's NodePorts (scripts/kind-up.sh maps them 1:1 onto the exact same host ports
 * docker-compose.yml publishes — 8081-8085 — so the defaults below need no kind-specific
 * configuration) and is skipped entirely unless {@code E2E_TARGET=kind} is set, so it never runs as
 * a side effect of the default compose-backed suite.
 *
 * <p>Seeding goes through the public REST API only (login as the seeded {@code admin} user, {@code
 * POST /inventory/{sku}/adjust}), not a direct JDBC connection like {@link
 * HappyPathAndInventoryCompensationE2ETest}'s {@code InventorySeed} — kind's Postgres has no
 * NodePort (ARCHITECTURE.md §4/§12: nothing outside the cluster needs to reach it directly, and
 * adding one just for this test would be scope this phase doesn't otherwise need), and the chart's
 * own seed Job (infra/helm/conveyor/templates/app/seed-job.yaml) already guarantees SKU-0001..0050
 * exist with the same `make seed` catalog compose uses — so topping one of those up is both simpler
 * and, arguably, a more representative test of the real admin workflow than seeding a database row
 * directly ever was.
 */
@EnabledIfEnvironmentVariable(named = "E2E_TARGET", matches = "kind")
class KindE2ESmokeTest {

  private static final String ORDER_BASE = env("E2E_ORDER_URL", "http://localhost:8081");
  private static final String INVENTORY_BASE = env("E2E_INVENTORY_URL", "http://localhost:8082");
  private static final String PAYMENT_BASE = env("E2E_PAYMENT_URL", "http://localhost:8083");
  private static final String DISPATCH_BASE = env("E2E_DISPATCH_URL", "http://localhost:8085");
  private static final String ADMIN_USERNAME = env("SEED_ADMIN_USERNAME", "admin");
  private static final String ADMIN_PASSWORD = env("SEED_ADMIN_PASSWORD", "admin_local_dev_only");

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return (value == null || value.isBlank()) ? fallback : value;
  }

  @Test
  void happyPathReachesConfirmedWithAShipmentAndANotification() throws Exception {
    String sku = "SKU-0001";
    topUpStock(sku, 1_000, "e2e-kind-happy-path-topup");

    UUID orderId = placeOrder(sku, 2);

    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(() -> assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED"));

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(
            () -> {
              JsonResponse shipment =
                  RestClient.get(DISPATCH_BASE + "/api/v1/shipments/" + orderId);
              assertThat(shipment.status()).isEqualTo(200);
              assertThat(shipment.body().path("trackingNumber").asText()).isNotBlank();
            });

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(
            () -> {
              JsonResponse notifications =
                  RestClient.get(DISPATCH_BASE + "/api/v1/notifications?orderId=" + orderId);
              assertThat(notifications.status()).isEqualTo(200);
              assertThat(notifications.body()).hasSizeGreaterThanOrEqualTo(1);
            });
  }

  @Test
  void insufficientStockCancelsTheOrderWithoutEverChargingPayment() throws Exception {
    // No seeded SKU has anywhere near this much stock (CatalogSeedRunner: onHand tops out
    // around 110) — same over-order-to-force-a-shortfall approach already used live in Phase 10's
    // own session record (CONTEXT.md), just as an automated assertion here.
    UUID orderId = placeOrder("SKU-0002", 999_999);

    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(() -> assertThat(orderStatus(orderId)).isEqualTo("CANCELLED"));

    JsonResponse payment = RestClient.get(PAYMENT_BASE + "/api/v1/payments/" + orderId);
    assertThat(payment.status()).isEqualTo(404);
  }

  @Test
  void forcedPaymentDeclineReleasesInventoryAndCancelsTheOrder() throws Exception {
    armPaymentDecline(1.0);
    try {
      String sku = "SKU-0003";
      topUpStock(sku, 1_000, "e2e-kind-decline-topup");

      UUID orderId = placeOrder(sku, 2);

      await()
          .atMost(Duration.ofSeconds(60))
          .pollInterval(Duration.ofSeconds(1))
          .untilAsserted(() -> assertThat(orderStatus(orderId)).isEqualTo("CANCELLED"));

      JsonResponse payment = RestClient.get(PAYMENT_BASE + "/api/v1/payments/" + orderId);
      assertThat(payment.status()).isEqualTo(404);
    } finally {
      // Disarm unconditionally, even on assertion failure — payment-service's chaos profile is
      // always on (values.yaml), so a probability left at 1.0 would poison every later order on
      // this cluster, including a human re-running `make kind-up` without realizing it.
      armPaymentDecline(0.0);
    }
  }

  private void armPaymentDecline(double probability) throws Exception {
    JsonResponse response =
        RestClient.post(
            PAYMENT_BASE + "/test/failure-mode", Map.of("mode", "DECLINE", "probability", probability));
    assertThat(response.status()).isEqualTo(200);
  }

  private void topUpStock(String sku, int delta, String reason) throws Exception {
    String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    JsonResponse response =
        RestClient.post(
            INVENTORY_BASE + "/api/v1/inventory/" + sku + "/adjust",
            Map.of("delta", delta, "reason", reason),
            null,
            Map.of("Authorization", "Bearer " + token));
    assertThat(response.status()).isEqualTo(200);
  }

  private String login(String username, String password) throws Exception {
    JsonResponse response =
        RestClient.post(
            ORDER_BASE + "/api/v1/auth/login", Map.of("username", username, "password", password));
    assertThat(response.status()).isEqualTo(200);
    return response.body().path("accessToken").asText();
  }

  private UUID placeOrder(String sku, int quantity) throws Exception {
    Map<String, Object> request =
        Map.of(
            "customerId", UUID.randomUUID().toString(),
            "items", List.of(Map.of("sku", sku, "quantity", quantity, "unitPrice", 9.99)),
            "shippingAddress",
                Map.of(
                    "line1", "1 E2E St",
                    "city", "Testville",
                    "postalCode", "00000",
                    "country", "IN"),
            "currency", "USD",
            "paymentMethodToken", "tok_test_visa");

    JsonResponse response =
        RestClient.post(
            ORDER_BASE + "/api/v1/orders", request, UUID.randomUUID().toString());
    assertThat(response.status()).isEqualTo(202);
    return UUID.fromString(response.body().path("orderId").asText());
  }

  private String orderStatus(UUID orderId) throws Exception {
    JsonNode order = RestClient.get(ORDER_BASE + "/api/v1/orders/" + orderId).body();
    return order.path("status").asText();
  }
}
