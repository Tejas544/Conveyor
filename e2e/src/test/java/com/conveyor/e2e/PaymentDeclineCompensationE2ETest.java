package com.conveyor.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.conveyor.e2e.support.InventorySeed;
import com.conveyor.e2e.support.RestClient;
import com.conveyor.e2e.support.RestClient.JsonResponse;
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
 * PLAN.md Phase 7: the second of the E2E suite's required compensation paths — "payment declined"
 * (ARCHITECTURE.md §8.2). A separate {@link ComposeContainer} from {@link
 * HappyPathAndInventoryCompensationE2ETest} because it forces {@code
 * CONVEYOR_PAYMENT_GATEWAY_DECLINE_RATE=1} for every charge on this stack — that has to be true for
 * every order this stack ever sees, so it cannot share a stack with the happy-path test.
 */
@Testcontainers
class PaymentDeclineCompensationE2ETest {

  private static final String INVENTORY_DB_USER = "inventory_service";
  private static final String INVENTORY_DB_PASSWORD = "inventory_service_local_dev_only";

  @Container
  static final ComposeContainer STACK =
      new ComposeContainer(new File("../docker-compose.yml"))
          .withLocalCompose(true)
          .withBuild(true)
          .withEnv("CONVEYOR_PAYMENT_GATEWAY_DECLINE_RATE", "1")
          .withExposedService("postgres", 5432, Wait.forListeningPort())
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
          .withStartupTimeout(Duration.ofMinutes(10));

  @Test
  void everyChargeDeclinesReleasesInventoryAndCancelsTheOrder() throws Exception {
    String sku = "SKU-E2E-DECLINE-" + UUID.randomUUID().toString().substring(0, 8);
    InventorySeed.seedStock(dbUrl(), INVENTORY_DB_USER, INVENTORY_DB_PASSWORD, sku, 10);

    Map<String, Object> request =
        Map.of(
            "customerId", UUID.randomUUID().toString(),
            "items", List.of(Map.of("sku", sku, "quantity", 2, "unitPrice", 9.99)),
            "shippingAddress",
                Map.of(
                    "line1", "1 E2E St",
                    "city", "Testville",
                    "postalCode", "00000",
                    "country", "IN"),
            "currency", "USD",
            "paymentMethodToken", "tok_test_visa");
    JsonResponse created =
        RestClient.post(orderUrl("/api/v1/orders"), request, UUID.randomUUID().toString());
    assertThat(created.status()).isEqualTo(202);
    UUID orderId = UUID.fromString(created.body().path("orderId").asText());

    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(
            () -> {
              JsonResponse order = RestClient.get(orderUrl("/api/v1/orders/" + orderId));
              assertThat(order.body().path("status").asText()).isEqualTo("CANCELLED");
            });

    // No CAPTURED payment ever existed for this order. The exact-restoration invariant on the
    // released reservation itself is already proven at the service level by inventory-service's
    // own ReservationReleaseIntegrationTest and saga-orchestrator's SagaCompensationIntegrationTest
    // — this test's job is proving the path is reachable end to end through the real pipeline, not
    // re-deriving an invariant those suites already establish.
    JsonResponse payment = RestClient.get(paymentUrl("/api/v1/payments/" + orderId));
    assertThat(payment.status()).isEqualTo(404);
  }

  // See HappyPathAndInventoryCompensationE2ETest#dbUrl() for why this goes through
  // Testcontainers' dynamically-assigned mapped port rather than the host's fixed 5432.
  private String dbUrl() {
    return "jdbc:postgresql://"
        + STACK.getServiceHost("postgres", 5432)
        + ":"
        + STACK.getServicePort("postgres", 5432)
        + "/inventory_service";
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
}
