package com.conveyor.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.common.testsupport.TestJwtSupport;
import com.conveyor.inventory.domain.StockItem;
import com.conveyor.inventory.repository.StockAdjustmentRepository;
import com.conveyor.inventory.repository.StockItemRepository;
import com.conveyor.inventory.web.dto.AdjustStockRequest;
import com.conveyor.inventory.web.dto.InventoryDetailResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * PLAN.md Phase 4: {@code POST /adjust} writes {@code stock_adjustments} and requires {@code ADMIN}
 * (ADR-5). Tokens are minted directly with {@link TestJwtSupport} since ADR-5's {@code POST
 * /auth/login} issuer doesn't exist yet (CONTEXT.md's Key Decisions Log).
 */
class InventoryAdjustIntegrationTest extends AbstractIntegrationTest {

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private StockItemRepository stockItemRepository;
  @Autowired private StockAdjustmentRepository stockAdjustmentRepository;

  @Test
  void anonymousAndOpsRequestsAreRejectedOnlyAdminSucceedsAndIsAudited() {
    stockItemRepository.saveAndFlush(new StockItem("SKU-ADJ-1", 10, 0, 2));
    AdjustStockRequest body = new AdjustStockRequest(5, "restock");

    ResponseEntity<String> anonymousResponse =
        restTemplate.postForEntity("/api/v1/inventory/SKU-ADJ-1/adjust", body, String.class);
    assertThat(anonymousResponse.getStatusCode().is4xxClientError()).isTrue();

    HttpHeaders opsHeaders = new HttpHeaders();
    opsHeaders.setBearerAuth(TestJwtSupport.token("ops-user", "OPS"));
    ResponseEntity<String> opsResponse =
        restTemplate.exchange(
            "/api/v1/inventory/SKU-ADJ-1/adjust",
            HttpMethod.POST,
            new HttpEntity<>(body, opsHeaders),
            String.class);
    assertThat(opsResponse.getStatusCode().is4xxClientError()).isTrue();
    assertThat(opsResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    HttpHeaders adminHeaders = new HttpHeaders();
    adminHeaders.setBearerAuth(TestJwtSupport.token("admin-user", "ADMIN"));
    ResponseEntity<InventoryDetailResponse> adminResponse =
        restTemplate.exchange(
            "/api/v1/inventory/SKU-ADJ-1/adjust",
            HttpMethod.POST,
            new HttpEntity<>(body, adminHeaders),
            InventoryDetailResponse.class);
    assertThat(adminResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(adminResponse.getBody().onHand()).isEqualTo(15);

    var adjustments = stockAdjustmentRepository.findAll();
    assertThat(adjustments).hasSize(1);
    assertThat(adjustments.get(0).getActor()).isEqualTo("admin-user");
    assertThat(adjustments.get(0).getDelta()).isEqualTo(5);
    assertThat(adjustments.get(0).getReason()).isEqualTo("restock");
  }

  @Test
  void adjustmentThatWouldDropOnHandBelowReservedIsRejected() {
    stockItemRepository.saveAndFlush(new StockItem("SKU-ADJ-2", 10, 8, 2));
    HttpHeaders adminHeaders = new HttpHeaders();
    adminHeaders.setBearerAuth(TestJwtSupport.token("admin-user", "ADMIN"));

    ResponseEntity<String> response =
        restTemplate.exchange(
            "/api/v1/inventory/SKU-ADJ-2/adjust",
            HttpMethod.POST,
            new HttpEntity<>(new AdjustStockRequest(-5, "damaged"), adminHeaders),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(stockItemRepository.findById("SKU-ADJ-2").orElseThrow().getOnHand()).isEqualTo(10);
  }
}
