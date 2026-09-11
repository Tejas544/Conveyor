package com.conveyor.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.conveyor.e2e.support.InventorySeed;
import com.conveyor.e2e.support.RestClient;
import com.conveyor.e2e.support.RestClient.JsonResponse;
import java.io.File;
import java.security.SecureRandom;
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
 * PLAN.md Phase 9's headline exit criterion: "an integration test asserts a single traceId appears
 * in spans from all five services for one order — trace propagation is tested, not eyeballed." Runs
 * the `observability` compose profile so Tempo is up to receive the export.
 *
 * <p>The test supplies its own W3C {@code traceparent} header on {@code POST /orders} (rather than
 * discovering whatever trace ID order-service happened to generate), which is the standard way an
 * already-instrumented caller hands a trace onward — Spring's tracing filter continues a valid
 * incoming trace instead of starting a new one. That pins a known trace ID for the whole saga,
 * which then has to show up, via Kafka header propagation (spring.kafka.*.observation- enabled), in
 * spans from all five services.
 *
 * <p>Requires {@code mvn -f e2e/pom.xml verify -DskipE2E=false} like the other E2E tests; unlike
 * them, this one also needs Tempo, so it brings the stack up with {@code --profile observability}.
 */
@Testcontainers
class TraceContextPropagationE2ETest {

  private static final String DB_USER = "inventory_service";
  private static final String DB_PASSWORD = "inventory_service_local_dev_only";
  private static final SecureRandom RANDOM = new SecureRandom();

  @Container
  static final ComposeContainer STACK =
      new ComposeContainer(new File("../docker-compose.yml"))
          .withLocalCompose(true)
          .withBuild(true)
          .withOptions("--profile", "observability")
          .withExposedService("postgres", 5432, Wait.forListeningPort())
          .withExposedService("tempo", 3200, Wait.forListeningPort())
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
  void oneOrderIsOneTraceAcrossAllFiveServices() throws Exception {
    String sku = "SKU-E2E-TRACE-" + UUID.randomUUID().toString().substring(0, 8);
    InventorySeed.seedStock(dbUrl(), DB_USER, DB_PASSWORD, sku, 10);

    String traceId = randomHex(32);
    String rootSpanId = randomHex(16);
    String traceparent = "00-" + traceId + "-" + rootSpanId + "-01";

    UUID orderId = placeOrder(sku, 2, traceparent);

    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(() -> assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED"));

    await()
        .atMost(Duration.ofSeconds(90))
        .pollInterval(Duration.ofSeconds(2))
        .untilAsserted(
            () -> {
              JsonResponse trace = RestClient.get(tempoUrl("/api/traces/" + traceId));
              assertThat(trace.status()).isEqualTo(200);
              String body = trace.body().toString();
              assertThat(body)
                  .as("trace %s should contain spans from every service", traceId)
                  .contains("order-service")
                  .contains("inventory-service")
                  .contains("payment-service")
                  .contains("saga-orchestrator")
                  .contains("dispatch-service");
            });
  }

  private UUID placeOrder(String sku, int quantity, String traceparent) throws Exception {
    Map<String, Object> request =
        Map.of(
            "customerId", UUID.randomUUID().toString(),
            "items", List.of(Map.of("sku", sku, "quantity", quantity, "unitPrice", 9.99)),
            "shippingAddress",
                Map.of(
                    "line1", "1 Trace St",
                    "city", "Testville",
                    "postalCode", "00000",
                    "country", "IN"),
            "currency", "USD",
            "paymentMethodToken", "tok_test_visa");

    JsonResponse response =
        RestClient.post(
            orderUrl("/api/v1/orders"),
            request,
            UUID.randomUUID().toString(),
            Map.of("traceparent", traceparent));
    assertThat(response.status()).isEqualTo(202);
    return UUID.fromString(response.body().path("orderId").asText());
  }

  private String orderStatus(UUID orderId) throws Exception {
    return RestClient.get(orderUrl("/api/v1/orders/" + orderId)).body().path("status").asText();
  }

  private static String randomHex(int length) {
    StringBuilder sb = new StringBuilder(length);
    while (sb.length() < length) {
      sb.append(Integer.toHexString(RANDOM.nextInt(16)));
    }
    return sb.toString();
  }

  // See HappyPathAndInventoryCompensationE2ETest for why this goes through Testcontainers'
  // dynamically-assigned mapped port rather than the host's published 5432.
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

  private String tempoUrl(String path) {
    return "http://"
        + STACK.getServiceHost("tempo", 3200)
        + ":"
        + STACK.getServicePort("tempo", 3200)
        + path;
  }
}
