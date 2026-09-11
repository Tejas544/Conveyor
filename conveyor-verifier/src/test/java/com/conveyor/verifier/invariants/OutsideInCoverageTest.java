package com.conveyor.verifier.invariants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.conveyor.verifier.config.ServiceClients;
import com.conveyor.verifier.domain.CheckOutcome;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * ARCHITECTURE.md §13's "measured" requirement: outside-in mode is exercised against the exact JSON
 * shapes the five services' real controllers return (verified against the DTO source, not guessed),
 * via {@link MockRestServiceServer} rather than a live stack — no different in spirit from any
 * other REST client's own unit tests. A representative sample across the observable/not-observable
 * split: the not-observable ones (default method, no override — trivially confirmed), one
 * same-service check, one cross-service check (the headline), and the one ARCHITECTURE.md §13
 * explicitly claims an outside-in checker can't see but this one, aggregating across services,
 * actually can.
 */
class OutsideInCoverageTest {

  private ServiceClients clientsFor(Map<String, MockRestServiceServer> servers) {
    Map<String, RestClient> clients = new HashMap<>();
    for (String key : new String[] {"order", "inventory", "payment", "saga", "dispatch"}) {
      RestClient.Builder builder = RestClient.builder();
      servers.put(key, MockRestServiceServer.bindTo(builder).build());
      clients.put(key, builder.build());
    }
    return new ServiceClients(clients);
  }

  @Test
  void inv01IsObservableAndDetectsAnOversellThroughTheInventoryListEndpoint() {
    Map<String, MockRestServiceServer> servers = new HashMap<>();
    ServiceClients clients = clientsFor(servers);
    servers
        .get("inventory")
        .expect(requestTo("/inventory?page=0&size=200"))
        .andRespond(
            withSuccess(
                """
                {"content":[{"sku":"SKU-1","onHand":5,"reserved":9,"available":-4,"reorderLevel":2}],"last":true}
                """,
                MediaType.APPLICATION_JSON));

    CheckOutcome outcome = new InvInv01NoOversellInvariant().checkOutsideIn(clients);
    assertThat(outcome.observable()).isTrue();
    assertThat(outcome.violations()).anyMatch(v -> v.offendingId().equals("SKU-1"));
  }

  @Test
  void inv01IsCleanWhenNoSkuOversells() {
    Map<String, MockRestServiceServer> servers = new HashMap<>();
    ServiceClients clients = clientsFor(servers);
    servers
        .get("inventory")
        .expect(requestTo("/inventory?page=0&size=200"))
        .andRespond(
            withSuccess(
                """
                {"content":[{"sku":"SKU-1","onHand":5,"reserved":2,"available":3,"reorderLevel":2}],"last":true}
                """,
                MediaType.APPLICATION_JSON));

    CheckOutcome outcome = new InvInv01NoOversellInvariant().checkOutsideIn(clients);
    assertThat(outcome.isClean()).isTrue();
  }

  @Test
  void inv0rd01IsObservableAcrossTwoServicesAndDetectsTheMissingCommittedReservation() {
    Map<String, MockRestServiceServer> servers = new HashMap<>();
    ServiceClients clients = clientsFor(servers);
    String orderId = "11111111-1111-1111-1111-111111111111";
    servers
        .get("order")
        .expect(requestTo("/orders?page=0&size=200"))
        .andRespond(
            withSuccess(
                """
                {"content":[{"orderId":"%s","status":"CONFIRMED",
                  "items":[{"sku":"SKU-1","quantity":2,"unitPrice":5.00}]}],"last":true}
                """
                    .formatted(orderId),
                MediaType.APPLICATION_JSON));
    servers
        .get("inventory")
        .expect(requestTo("/inventory/SKU-1/reservations"))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

    CheckOutcome outcome =
        new InvOrd01ConfirmedHasCommittedReservationInvariant().checkOutsideIn(clients);
    assertThat(outcome.observable()).isTrue();
    assertThat(outcome.violations()).anyMatch(v -> v.offendingId().equals(orderId));
  }

  @Test
  void inv0rd02DetectsAHeldReservationOnACancelledOrderByAggregatingTwoServices() {
    // ARCHITECTURE.md §13's own illustrative claim ("invisible to GET /orders/{id}") only holds for
    // a checker that calls one endpoint. This one also calls inventory-service and does see it.
    Map<String, MockRestServiceServer> servers = new HashMap<>();
    ServiceClients clients = clientsFor(servers);
    String orderId = "22222222-2222-2222-2222-222222222222";
    servers
        .get("order")
        .expect(requestTo("/orders?page=0&size=200"))
        .andRespond(
            withSuccess(
                """
                {"content":[{"orderId":"%s","status":"CANCELLED","items":[]}],"last":true}
                """
                    .formatted(orderId),
                MediaType.APPLICATION_JSON));
    servers
        .get("inventory")
        .expect(requestTo("/inventory?page=0&size=200"))
        .andRespond(
            withSuccess(
                """
                {"content":[{"sku":"SKU-1","onHand":5,"reserved":2,"available":3,"reorderLevel":2}],"last":true}
                """,
                MediaType.APPLICATION_JSON));
    servers
        .get("inventory")
        .expect(requestTo("/inventory/SKU-1/reservations"))
        .andRespond(
            withSuccess(
                """
                [{"id":"33333333-3333-3333-3333-333333333333","orderId":"%s","sku":"SKU-1","quantity":2,"status":"HELD"}]
                """
                    .formatted(orderId),
                MediaType.APPLICATION_JSON));

    CheckOutcome outcome =
        new InvOrd02CancelledHasNoHeldReservationInvariant().checkOutsideIn(clients);
    assertThat(outcome.observable()).isTrue();
    assertThat(outcome.violations()).anyMatch(v -> v.offendingId().equals(orderId));
  }

  @Test
  void inv04IsObservableAndDetectsAStuckSagaThroughTheSagaListEndpoint() {
    Map<String, MockRestServiceServer> servers = new HashMap<>();
    ServiceClients clients = clientsFor(servers);
    String sagaId = "44444444-4444-4444-4444-444444444444";
    servers
        .get("saga")
        .expect(requestTo("/sagas?state=NEEDS_INTERVENTION"))
        .andRespond(
            withSuccess(
                """
                [{"sagaId":"%s","orderId":"55555555-5555-5555-5555-555555555555","state":"NEEDS_INTERVENTION"}]
                """
                    .formatted(sagaId),
                MediaType.APPLICATION_JSON));

    CheckOutcome outcome = new InvSaga04NoNeedsInterventionInvariant().checkOutsideIn(clients);
    assertThat(outcome.observable()).isTrue();
    assertThat(outcome.violations()).anyMatch(v -> v.offendingId().equals(sagaId));
  }

  @Test
  void inv03Inv2Pay01AndBox01AreNotObservableOutsideIn() {
    Map<String, MockRestServiceServer> servers = new HashMap<>();
    ServiceClients clients = clientsFor(servers);

    assertThat(new InvInv03ConservationInvariant().checkOutsideIn(clients).observable()).isFalse();
    assertThat(new InvPay01AtMostOneCapturedPaymentInvariant().checkOutsideIn(clients).observable())
        .isFalse();
    assertThat(new InvBox01NoStaleOutboxRowInvariant(null).checkOutsideIn(clients).observable())
        .isFalse();
    // No HTTP calls expected for any of the three — the default method never touches
    // ServiceClients.
    servers.values().forEach(MockRestServiceServer::verify);
  }
}
