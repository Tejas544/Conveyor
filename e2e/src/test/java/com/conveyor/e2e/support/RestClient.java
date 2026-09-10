package com.conveyor.e2e.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * A minimal, dependency-light HTTP client for the e2e suite — deliberately not Spring's {@code
 * RestClient}/{@code WebClient}: this module has no Spring context of its own (PLAN.md Phase 7:
 * "driving the public REST API only"), so a plain {@link HttpClient} is the honest choice rather
 * than pulling in a web framework just to make GET/POST calls.
 */
public final class RestClient {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final HttpClient HTTP_CLIENT =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private RestClient() {}

  public record JsonResponse(int status, JsonNode body) {}

  public static JsonResponse get(String url) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
    return send(request);
  }

  public static JsonResponse post(String url, Object bodyObject)
      throws IOException, InterruptedException {
    return post(url, bodyObject, null);
  }

  public static JsonResponse post(String url, Object bodyObject, String idempotencyKey)
      throws IOException, InterruptedException {
    String json = OBJECT_MAPPER.writeValueAsString(bodyObject);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json));
    if (idempotencyKey != null) {
      builder.header("Idempotency-Key", idempotencyKey);
    }
    return send(builder.build());
  }

  private static JsonResponse send(HttpRequest request) throws IOException, InterruptedException {
    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    JsonNode body =
        response.body() == null || response.body().isBlank()
            ? OBJECT_MAPPER.nullNode()
            : OBJECT_MAPPER.readTree(response.body());
    return new JsonResponse(response.statusCode(), body);
  }
}
