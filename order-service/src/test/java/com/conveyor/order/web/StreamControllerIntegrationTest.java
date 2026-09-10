package com.conveyor.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.common.testsupport.TestJwtSupport;
import com.conveyor.order.sse.SseBroadcaster;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * PLAN.md Phase 8, ADR-5/ADR-10: the SSE stream is authenticated (OPS), fans an in-process
 * published event out to every connected client, honours {@code ?orderId=} filtering, and replays
 * buffered events past {@code Last-Event-ID} on reconnect. Driven with a raw {@link HttpClient}
 * reading the response as a stream, the same style ADR-5's frontend note describes (a plain HTTP
 * request with an {@code Authorization} header, not the browser's {@code EventSource}).
 */
class StreamControllerIntegrationTest extends AbstractIntegrationTest {

  @LocalServerPort private int port;
  @Autowired private SseBroadcaster broadcaster;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @Test
  void anonymousRequestsAreRejected() throws Exception {
    HttpRequest request = HttpRequest.newBuilder(streamUri(null)).GET().build();
    HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
    assertThat(response.statusCode()).isBetween(400, 499);
  }

  @Test
  void anOpsTokenReceivesALivePublishedEvent() throws Exception {
    UUID orderId = UUID.randomUUID();
    HttpRequest request =
        HttpRequest.newBuilder(streamUri(null))
            .header("Authorization", "Bearer " + TestJwtSupport.token("ops-user", "OPS"))
            .GET()
            .build();

    HttpResponse<java.io.InputStream> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    assertThat(response.statusCode()).isEqualTo(200);

    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
      awaitSubscriberRegistration();
      broadcaster.publish(
          "order.step",
          orderId.toString(),
          Map.of("orderId", orderId.toString(), "hello", "world"));

      String frame = readUntilEventFrame(reader, "order.step", Duration.ofSeconds(10));
      assertThat(frame).contains("\"orderId\":\"" + orderId + "\"");
    }
  }

  @Test
  void orderIdFilterOnlyDeliversMatchingEvents() throws Exception {
    UUID wantedOrder = UUID.randomUUID();
    UUID otherOrder = UUID.randomUUID();
    HttpRequest request =
        HttpRequest.newBuilder(streamUri(wantedOrder.toString()))
            .header("Authorization", "Bearer " + TestJwtSupport.token("ops-user", "OPS"))
            .GET()
            .build();

    HttpResponse<java.io.InputStream> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
      awaitSubscriberRegistration();
      broadcaster.publish(
          "order.step",
          otherOrder.toString(),
          Map.of("orderId", otherOrder.toString(), "noise", true));
      broadcaster.publish(
          "order.step",
          wantedOrder.toString(),
          Map.of("orderId", wantedOrder.toString(), "signal", true));

      String frame = readUntilEventFrame(reader, "order.step", Duration.ofSeconds(10));
      assertThat(frame).contains(wantedOrder.toString()).doesNotContain(otherOrder.toString());
    }
  }

  @Test
  void reconnectingWithLastEventIdReplaysMissedEvents() throws Exception {
    UUID orderId = UUID.randomUUID();
    // Published with nobody connected -- lands in the broadcaster's buffer only.
    broadcaster.publish(
        "order.step",
        orderId.toString(),
        Map.of("orderId", orderId.toString(), "beforeReconnect", true));

    HttpRequest request =
        HttpRequest.newBuilder(streamUri(null))
            .header("Authorization", "Bearer " + TestJwtSupport.token("ops-user", "OPS"))
            .header("Last-Event-ID", "0")
            .GET()
            .build();

    HttpResponse<java.io.InputStream> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
      String frame = readUntilEventFrame(reader, "order.step", Duration.ofSeconds(10));
      assertThat(frame).contains(orderId.toString()).contains("beforeReconnect");
    }
  }

  private URI streamUri(String orderId) {
    String query = orderId == null ? "" : "?orderId=" + orderId;
    return URI.create("http://localhost:" + port + "/api/v1/stream/orders" + query);
  }

  /**
   * No signal for "the server has registered the subscription yet" other than a short, generous
   * wait.
   */
  private void awaitSubscriberRegistration() throws InterruptedException {
    TimeUnit.MILLISECONDS.sleep(300);
  }

  private String readUntilEventFrame(BufferedReader reader, String eventName, Duration timeout)
      throws Exception {
    long deadline = System.currentTimeMillis() + timeout.toMillis();
    boolean sawEventLine = false;
    String line;
    while (System.currentTimeMillis() < deadline && (line = reader.readLine()) != null) {
      if (line.equals("event:" + eventName)) {
        sawEventLine = true;
        continue;
      }
      if (sawEventLine && line.startsWith("data:")) {
        return line.substring("data:".length());
      }
    }
    throw new AssertionError("Timed out waiting for SSE event '" + eventName + "'");
  }
}
