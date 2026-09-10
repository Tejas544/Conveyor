package com.conveyor.saga.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.common.testsupport.TestJwtSupport;
import com.conveyor.contracts.events.OrderItemPayload;
import com.conveyor.saga.domain.SagaInstance;
import com.conveyor.saga.domain.SagaState;
import com.conveyor.saga.repository.SagaInstanceRepository;
import com.conveyor.saga.service.SagaOrchestrationService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** ARCHITECTURE.md §10.2: read endpoints are OPS-readable; retry/abort require ADMIN (ADR-5). */
class SagaControllerIntegrationTest extends AbstractIntegrationTest {

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private SagaOrchestrationService orchestrationService;
  @Autowired private SagaInstanceRepository sagaInstanceRepository;

  @Test
  void getByOrderIdReturnsTheStepLog() {
    UUID orderId = startSaga();

    ResponseEntity<String> response =
        restTemplate.getForEntity("/api/v1/sagas/" + orderId, String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("RESERVING_INVENTORY").contains("RESERVE_INVENTORY");
  }

  @Test
  void listSupportsStuckFilter() {
    UUID orderId = startSaga();
    SagaInstance saga = sagaInstanceRepository.findByOrderId(orderId).orElseThrow();
    saga.setDeadlineAt(Instant.now().minusSeconds(5));
    sagaInstanceRepository.saveAndFlush(saga);

    ResponseEntity<String> response =
        restTemplate.getForEntity("/api/v1/sagas?stuck=true", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains(orderId.toString());
  }

  @Test
  void retryRequiresAdminAndRedrivesAStuckSaga() {
    UUID orderId = startSaga();
    orchestrationService.handleInventoryReserved(
        UUID.randomUUID(),
        orderId,
        List.of(UUID.randomUUID()),
        List.of(new com.conveyor.contracts.events.InventoryItemPayload("SKU-1", 1)));
    orchestrationService.handlePaymentFailed(UUID.randomUUID(), orderId, "DECLINED");

    SagaInstance saga = sagaInstanceRepository.findByOrderId(orderId).orElseThrow();
    assertThat(saga.getState()).isEqualTo(SagaState.COMPENSATING_INVENTORY);
    // Simulate compensation having exhausted its retries (SagaTimeoutIntegrationTest covers the
    // sweep escalating this for real) — currentStep and the RESERVE_INVENTORY log entry are the
    // real ones, only the "gave up" transition itself is short-circuited here.
    saga.setState(SagaState.NEEDS_INTERVENTION);
    saga.setDeadlineAt(null);
    sagaInstanceRepository.saveAndFlush(saga);

    ResponseEntity<String> anonymous =
        restTemplate.postForEntity("/api/v1/sagas/" + saga.getId() + "/retry", null, String.class);
    assertThat(anonymous.getStatusCode().is4xxClientError()).isTrue();

    HttpHeaders opsHeaders = new HttpHeaders();
    opsHeaders.setBearerAuth(TestJwtSupport.token("ops-user", "OPS"));
    ResponseEntity<String> opsResponse =
        restTemplate.exchange(
            "/api/v1/sagas/" + saga.getId() + "/retry",
            HttpMethod.POST,
            new HttpEntity<>(opsHeaders),
            String.class);
    assertThat(opsResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    HttpHeaders adminHeaders = new HttpHeaders();
    adminHeaders.setBearerAuth(TestJwtSupport.token("admin-user", "ADMIN"));
    ResponseEntity<String> adminResponse =
        restTemplate.exchange(
            "/api/v1/sagas/" + saga.getId() + "/retry",
            HttpMethod.POST,
            new HttpEntity<>(adminHeaders),
            String.class);
    assertThat(adminResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(sagaInstanceRepository.findById(saga.getId()).orElseThrow().getState())
        .isEqualTo(SagaState.COMPENSATING_INVENTORY);
  }

  private UUID startSaga() {
    UUID orderId = UUID.randomUUID();
    orchestrationService.startSaga(
        UUID.randomUUID(),
        orderId,
        List.of(new OrderItemPayload("SKU-1", 1, new BigDecimal("5.00"))),
        new BigDecimal("5.00"),
        "USD",
        "tok_test_visa");
    return orderId;
  }
}
