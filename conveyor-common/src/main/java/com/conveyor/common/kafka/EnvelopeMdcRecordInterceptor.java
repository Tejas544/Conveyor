package com.conveyor.common.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;

/**
 * Populates {@code sagaId} / {@code orderId} / {@code eventType} into MDC from the envelope before
 * a Kafka listener method runs, so every log line a consumer emits while handling a message is
 * attributable to the order and saga it belongs to — without every service doing this by hand
 * (ARCHITECTURE.md §11). {@code traceId}/{@code spanId} are populated separately, by Micrometer
 * Tracing's OTel bridge (Phase 9) once {@code spring.kafka.listener.observation-enabled=true} links
 * the consume span to the producer's; this interceptor only knows about envelope fields.
 *
 * <p>Implements {@code RecordInterceptor<Object, Object>}, not {@code <String, String>}, even
 * though every consumer in this codebase uses {@link
 * org.apache.kafka.common.serialization.StringDeserializer}: Spring Boot's {@code
 * KafkaAnnotationDrivenConfiguration} looks up this bean via {@code
 * ObjectProvider<RecordInterceptor<Object, Object>>}, and Java generics are invariant, so a {@code
 * RecordInterceptor<String, String>} bean silently never matches and is never wired into the
 * listener container factory — found as BUG-0019 the first time a live log line was actually
 * inspected for {@code orderId}/{@code sagaId}, rather than just unit-testing this class in
 * isolation. The cast to {@link String} below is safe because every consumer's value deserializer
 * is, in fact, {@code StringDeserializer}.
 */
public class EnvelopeMdcRecordInterceptor implements RecordInterceptor<Object, Object> {

  private static final String MDC_ORDER_ID = "orderId";
  private static final String MDC_SAGA_ID = "sagaId";
  private static final String MDC_EVENT_TYPE = "eventType";

  private final ObjectMapper objectMapper;

  public EnvelopeMdcRecordInterceptor(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public ConsumerRecord<Object, Object> intercept(
      ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
    try {
      JsonNode envelope = objectMapper.readTree((String) record.value());
      putIfPresent(envelope, "orderId", MDC_ORDER_ID);
      putIfPresent(envelope, "sagaId", MDC_SAGA_ID);
      putIfPresent(envelope, "eventType", MDC_EVENT_TYPE);
    } catch (Exception ignoredMalformedPayload) {
      // Deliberately non-fatal: a malformed envelope is the listener's
      // problem to reject, not this interceptor's. Logging is skipped here
      // to avoid every parse failure being logged twice.
    }
    return record;
  }

  @Override
  public void afterRecord(
      ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
    MDC.remove(MDC_ORDER_ID);
    MDC.remove(MDC_SAGA_ID);
    MDC.remove(MDC_EVENT_TYPE);
  }

  private void putIfPresent(JsonNode envelope, String field, String mdcKey) {
    JsonNode value = envelope.get(field);
    if (value != null && !value.isNull()) {
      MDC.put(mdcKey, value.asText());
    }
  }
}
