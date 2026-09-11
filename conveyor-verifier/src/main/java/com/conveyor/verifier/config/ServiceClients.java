package com.conveyor.verifier.config;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
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
    VerifierProperties.Http http = properties.http();
    clients.put("order", RestClient.create(http.orderBaseUrl()));
    clients.put("inventory", RestClient.create(http.inventoryBaseUrl()));
    clients.put("payment", RestClient.create(http.paymentBaseUrl()));
    clients.put("saga", RestClient.create(http.sagaBaseUrl()));
    clients.put("dispatch", RestClient.create(http.dispatchBaseUrl()));
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
