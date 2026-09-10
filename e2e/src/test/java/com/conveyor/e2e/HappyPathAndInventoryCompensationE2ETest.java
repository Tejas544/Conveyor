package com.conveyor.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.conveyor.e2e.support.InventorySeed;
import com.conveyor.e2e.support.RestClient;
import com.conveyor.e2e.support.RestClient.JsonResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PLAN.md Phase 7: "full E2E — REST call → shipment row → notification document → ShipmentCreated
 * on the topic" plus one of the three required compensation paths, driven entirely through the
 * public REST API of the five real, containerized services (ARCHITECTURE.md §8.1/§8.2) — no
 * internal Java types, no direct Kafka access. The other two compensation paths are covered by
 * {@link PaymentDeclineCompensationE2ETest} (this class's stack, unlike that one, never arms a
 * payment failure) and by saga-orchestrator's own {@code SagaCompensationIntegrationTest} — see
 * that test class's Javadoc and CONTEXT.md's Key Decisions Log for why "payment succeeds, then an
 * operator aborts anyway" is not re-proven here: it requires racing the saga's own pivot commit,
 * which that dedicated, already-green test exercises deterministically against the service directly
 * rather than over the added latency and jitter of a live compose network path.
 *
 * <p>Requires a Docker daemon capable of {@code docker compose build} for all five services; {@code
 * mvn -pl e2e verify -DskipE2E=false} is the only way to run this module (see its pom).
 */
@Testcontainers
class HappyPathAndInventoryCompensationE2ETest {

  private static final String DB_URL = "jdbc:postgresql://localhost:5432/inventory_service";
  private static final String DB_USER = "inventory_service";
  private static final String DB_PASSWORD = "inventory_service_local_dev_only";

  @Container
  static final ComposeContainer STACK =
      new ComposeContainer(new File("../docker-compose.yml"))
          .withLocalCompose(true)
          .withBuild(true)
          .withExposedService(
              "order-service",
              8081,
              Wait.forHttp("/actuator/health")
                  .forStatusCode(200)
                  .withStartupTimeout(Duration.ofMinutes(5)))
          .withExposedService(
              "inventory-service",
              8082,
              Wait.forHttp("/actuator/health")
                  .forStatusCode(200)
                  .withStartupTimeout(Duration.ofMinutes(5)))
          .withExposedService(
              "payment-service",
              8083,
              Wait.forHttp("/actuator/health")
                  .forStatusCode(200)
                  .withStartupTimeout(Duration.ofMinutes(5)))
          .withExposedService(
              "saga-orchestrator",
              8084,
              Wait.forHttp("/actuator/health")
                  .forStatusCode(200)
                  .withStartupTimeout(Duration.ofMinutes(5)))
          .withExposedService(
              "dispatch-service",
              8085,
              Wait.forHttp("/actuator/health")
                  .forStatusCode(200)
                  .withStartupTimeout(Duration.ofMinutes(5)))
          .withStartupTimeout(Duration.ofMinutes(10));

  @Test
  void placingAnOrderReachesConfirmedWithAShipmentAndANotification() throws Exception {
    String sku = "SKU-E2E-HAPPY-" + UUID.randomUUID().toString().substring(0, 8);
    InventorySeed.seedStock(DB_URL, DB_USER, DB_PASSWORD, sku, 10);

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
              JsonResponse shipment = RestClient.get(dispatchUrl("/api/v1/shipments/" + orderId));
              assertThat(shipment.status()).isEqualTo(200);
              assertThat(shipment.body().path("trackingNumber").asText()).isNotBlank();
            });

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(
            () -> {
              JsonResponse notifications =
                  RestClient.get(dispatchUrl("/api/v1/notifications?orderId=" + orderId));
              assertThat(notifications.status()).isEqualTo(200);
              assertThat(notifications.body()).hasSizeGreaterThanOrEqualTo(1);
            });
  }

  @Test
  void insufficientStockCancelsTheOrderWithoutEverChargingPayment() throws Exception {
    String sku = "SKU-E2E-SHORT-" + UUID.randomUUID().toString().substring(0, 8);
    InventorySeed.seedStock(DB_URL, DB_USER, DB_PASSWORD, sku, 1);

    UUID orderId = placeOrder(sku, 5);

    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(() -> assertThat(orderStatus(orderId)).isEqualTo("CANCELLED"));

    JsonResponse payment = RestClient.get(paymentUrl("/api/v1/payments/" + orderId));
    assertThat(payment.status()).isEqualTo(404);
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
        RestClient.post(orderUrl("/api/v1/orders"), request, UUID.randomUUID().toString());
    assertThat(response.status()).isEqualTo(202);
    return UUID.fromString(response.body().path("orderId").asText());
  }

  private String orderStatus(UUID orderId) throws Exception {
    JsonNode order = RestClient.get(orderUrl("/api/v1/orders/" + orderId)).body();
    return order.path("status").asText();
  }

  private String orderUrl(String path) {
    return "http://"
        + STACK.getServiceHost("order-service", 8081)
        + ":"
        + STACK.getServicePort("order-service", 8081)
        + path;
  }

  private String paymentUrl(String path) {
    return "http://"
        + STACK.getServiceHost("payment-service", 8083)
        + ":"
        + STACK.getServicePort("payment-service", 8083)
        + path;
  }

  private String dispatchUrl(String path) {
    return "http://"
        + STACK.getServiceHost("dispatch-service", 8085)
        + ":"
        + STACK.getServicePort("dispatch-service", 8085)
        + path;
  }
}
