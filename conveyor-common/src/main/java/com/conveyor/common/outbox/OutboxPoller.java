package com.conveyor.common.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

/**
 * ARCHITECTURE.md §9, ADR-7. Publishes {@code outbox} rows that share this exact schema in every
 * service database (§5.6) — plain JDBC rather than JPA because {@code FOR UPDATE SKIP LOCKED} has
 * no clean ORM equivalent, and because that makes this class work identically for any service
 * regardless of that service's own JPA entity for the table. {@code payload} is stored as the
 * already-fully-formed {@link com.conveyor.common.envelope.ConveyorEnvelope} JSON, so publishing is
 * "read the row, send it, stamp it" with no reconstruction step.
 *
 * <p>At-least-once by construction: if the process dies after {@link KafkaTemplate#send} succeeds
 * but before the {@code UPDATE} below commits, the row is republished on the next poll. Every
 * consumer's inbox table is what turns that into exactly-once effects (§9).
 */
public class OutboxPoller {

  private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

  private static final String SELECT_BATCH_SQL =
      "SELECT id, topic, message_key, payload::text AS payload, headers::text AS headers "
          + "FROM outbox WHERE published_at IS NULL ORDER BY created_at LIMIT :batchSize "
          + "FOR UPDATE SKIP LOCKED";

  private static final String MARK_PUBLISHED_SQL =
      "UPDATE outbox SET published_at = :publishedAt, attempts = attempts + 1 WHERE id = :id";

  private final NamedParameterJdbcTemplate jdbcTemplate;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;
  private final OutboxPollerProperties properties;

  public OutboxPoller(
      NamedParameterJdbcTemplate jdbcTemplate,
      KafkaTemplate<String, String> kafkaTemplate,
      ObjectMapper objectMapper,
      OutboxPollerProperties properties) {
    this.jdbcTemplate = jdbcTemplate;
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
    this.properties = properties;
  }

  @Scheduled(fixedDelayString = "${conveyor.outbox.poller.interval-ms:200}")
  public void pollAndPublish() {
    if (!Boolean.TRUE.equals(properties.enabled())) {
      return;
    }
    publishOneBatch();
  }

  /**
   * Also called directly by tests that disable the scheduled trigger to drive publishing manually.
   */
  @Transactional
  public int publishOneBatch() {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            SELECT_BATCH_SQL, new MapSqlParameterSource("batchSize", properties.batchSize()));

    for (Map<String, Object> row : rows) {
      UUID id = (UUID) row.get("id");
      String topic = (String) row.get("topic");
      String messageKey = (String) row.get("message_key");
      String payload = (String) row.get("payload");
      String headersJson = (String) row.get("headers");

      try {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, messageKey, payload);
        addHeaders(record, headersJson);
        kafkaTemplate.send(record).get();
        markPublished(id);
      } catch (Exception e) {
        log.warn("Outbox row {} failed to publish to topic {}; will retry next poll", id, topic, e);
      }
    }
    return rows.size();
  }

  private void addHeaders(ProducerRecord<String, String> record, String headersJson) {
    if (headersJson == null || headersJson.isBlank()) {
      return;
    }
    try {
      Map<?, ?> headers = objectMapper.readValue(headersJson, Map.class);
      headers.forEach(
          (key, value) -> {
            if (value != null) {
              record.headers().add(String.valueOf(key), String.valueOf(value).getBytes());
            }
          });
    } catch (Exception e) {
      log.warn("Malformed outbox headers JSON, publishing without headers: {}", headersJson, e);
    }
  }

  private void markPublished(UUID id) {
    jdbcTemplate.update(
        MARK_PUBLISHED_SQL,
        new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("publishedAt", Timestamp.from(java.time.Instant.now())));
  }
}
