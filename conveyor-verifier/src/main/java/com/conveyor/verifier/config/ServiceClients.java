package com.conveyor.verifier.config;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Outside-in mode's only source of truth: five {@link RestClient}s, one per service, hitting
 * exactly the endpoints the dashboard and the public API already expose — no endpoint added for the
 * checker's own benefit, because that would quietly manufacture coverage the checker doesn't
 * actually have as a REST consumer (ARCHITECTURE.md §13's whole argument).
 *
 * <p>Plain {@code @Component}, not {@code @Configuration} — there are no {@code @Bean} factory
 * methods here needing CGLIB proxying, and (see BUGS.md) declaring it {@code @Configuration}
 * anyway, combined with the second, test-only constructor below, broke live bean instantiation:
 * Spring's configuration-class enhancer generated a proxy subclass whose constructor didn't match
 * either of this class's, and every unit test using the ordinary properties-based constructor
 * directly (never going through Spring) never exercised that path at all.
 */
@Component
public class ServiceClients {

  private final Map<String, RestClient> clients = new LinkedHashMap<>();

  @Autowired
  public ServiceClients(VerifierProperties properties) {
    // BUG-0053 (Phase 16, found live): RestClient.create(baseUrl) with no configured timeout
    // means a single transiently-unreachable service (e.g. mid-restart, exactly what a real
    // crash-recovery demo does) hangs the outside-in check -- and therefore the whole oneshot
    // run, since System.exit() only happens after both modes finish -- indefinitely. A CI-gating
    // tool that can hang forever instead of failing fast is worse than one that fails cleanly. A
    // bounded 5s connect/read timeout on every client fixes it: an unreachable service now shows
    // up as a real, fast "not observable"/error in the outside-in report rather than wedging the
    // whole run.
    ClientHttpRequestFactory requestFactory =
        ClientHttpRequestFactoryBuilder.detect()
            .build(
                ClientHttpRequestFactorySettings.defaults()
                    .withConnectTimeout(Duration.ofSeconds(5))
                    .withReadTimeout(Duration.ofSeconds(5)));
    VerifierProperties.Http http = properties.http();
    clients.put(
        "order",
        RestClient.builder().baseUrl(http.orderBaseUrl()).requestFactory(requestFactory).build());
    clients.put(
        "inventory",
        RestClient.builder()
            .baseUrl(http.inventoryBaseUrl())
            .requestFactory(requestFactory)
            .build());
    clients.put(
        "payment",
        RestClient.builder().baseUrl(http.paymentBaseUrl()).requestFactory(requestFactory).build());
    clients.put(
        "saga",
        RestClient.builder().baseUrl(http.sagaBaseUrl()).requestFactory(requestFactory).build());
    clients.put(
        "dispatch",
        RestClient.builder()
            .baseUrl(http.dispatchBaseUrl())
            .requestFactory(requestFactory)
            .build());
  }

  /**
   * Test-only: lets {@code OutsideInCoverageTest} bind a {@code MockRestServiceServer} per service.
   */
  public ServiceClients(Map<String, RestClient> clients) {
    this.clients.putAll(clients);
  }

  public RestClient get(String key) {
    RestClient client = clients.get(key);
    if (client == null) {
      throw new IllegalArgumentException("No such service client: " + key);
    }
    return client;
  }

  /**
   * A single object at {@code path}, or {@code null} on a 404 (e.g. "no shipment for this order").
   */
  public JsonNode getOrNull(String key, String path) {
    try {
      return get(key).get().uri(path).retrieve().body(JsonNode.class);
    } catch (HttpClientErrorException.NotFound e) {
      return null;
    }
  }

  /** A plain (non-paginated) JSON array endpoint. */
  public List<JsonNode> getArray(String key, String path) {
    JsonNode node = get(key).get().uri(path).retrieve().body(JsonNode.class);
    List<JsonNode> result = new ArrayList<>();
    if (node != null) {
      node.forEach(result::add);
    }
    return result;
  }

  /**
   * Every element of a Spring {@code Page} response's {@code content}, walking pages with the flat
   * {@code page}/{@code size} query params BUG-0015 established (never {@code pageable[...]}).
   */
  public List<JsonNode> getAllPages(String key, String pathAndQuery) {
    List<JsonNode> all = new ArrayList<>();
    int page = 0;
    String separator = pathAndQuery.contains("?") ? "&" : "?";
    while (true) {
      String uri = pathAndQuery + separator + "page=" + page + "&size=200";
      JsonNode body = get(key).get().uri(uri).retrieve().body(JsonNode.class);
      if (body == null) {
        return all;
      }
      body.path("content").forEach(all::add);
      if (body.path("last").asBoolean(true)) {
        return all;
      }
      page++;
    }
  }
}
