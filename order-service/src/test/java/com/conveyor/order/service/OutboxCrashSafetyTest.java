package com.conveyor.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.conveyor.common.kafka.KafkaTopics;
import com.conveyor.common.outbox.OutboxPoller;
import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.order.outbox.OutboxRecord;
import com.conveyor.order.outbox.OutboxRecordRepository;
import com.conveyor.order.testsupport.TestKafkaConsumers;
import com.conveyor.order.web.dto.CreateOrderItemRequest;
import com.conveyor.order.web.dto.CreateOrderRequest;
import com.conveyor.order.web.dto.ShippingAddressRequest;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * PLAN.md Phase 3 — <strong>the important test</strong>: outbox crash safety. The poller is
 * disabled for the whole test class ({@code application-test.yml}), so committing an order here is
 * exactly "the process is killed between the DB commit and the outbox ever getting a chance to
 * publish." On restart — modeled as the poller finally running, the same thing a fresh process's
 * first scheduled tick would do — the event is published exactly once and a consumer sees exactly
 * one message.
 */
class OutboxCrashSafetyTest extends AbstractIntegrationTest {

  @Autowired private OrderService orderService;
  @Autowired private OutboxRecordRepository outboxRecordRepository;
  @Autowired private OutboxPoller outboxPoller;

  @Test
  void eventSurvivesAAcrashBetweenCommitAndPublishAndIsPublishedExactlyOnceOnRestart() {
    CreateOrderRequest request =
        new CreateOrderRequest(
            UUID.randomUUID(),
            List.of(new CreateOrderItemRequest("SKU-CRASH-1", 1, new BigDecimal("5.00"))),
            new ShippingAddressRequest("1 Test St", "Testville", "00000", "IN"),
            "USD",
            "tok_test_visa");

    OrderCreationResult result = orderService.createOrder(request, null);
    UUID orderId = result.order().getId();

    // "Crash before publish": the row committed but nothing has run the poller yet.
    OutboxRecord row =
        outboxRecordRepository.findAll().stream()
            .filter(r -> r.getAggregateId().equals(orderId.toString()))
            .findFirst()
            .orElseThrow();
    assertThat(row.getPublishedAt()).isNull();

    try (Consumer<String, String> consumer =
        TestKafkaConsumers.subscribedTo(REDPANDA.getBootstrapServers(), KafkaTopics.ORDER_EVENTS)) {
      ConsumerRecords<String, String> beforeRestart = consumer.poll(Duration.ofSeconds(2));
      assertThat(beforeRestart.count()).isZero();

      // "Restart": a fresh process's poller runs for the first time and finds the unpublished row.
      outboxPoller.publishOneBatch();

      ConsumerRecords<String, String> afterRestart;
      try {
        afterRestart = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10), 1);
      } catch (KafkaException e) {
        throw new AssertionError("expected exactly one record after the poller ran", e);
      }
      assertThat(afterRestart.count()).isEqualTo(1);
      assertThat(afterRestart.iterator().next().key()).isEqualTo(orderId.toString());
    }

    OutboxRecord publishedRow = outboxRecordRepository.findById(row.getId()).orElseThrow();
    assertThat(publishedRow.getPublishedAt()).isNotNull();
  }
}
