package com.conveyor.saga.scheduling;

import com.conveyor.saga.service.SagaOrchestrationService;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * ARCHITECTURE.md §7.4: every {@code sweep-interval}, claims non-terminal sagas whose deadline has
 * passed with {@code FOR UPDATE SKIP LOCKED} — plain JDBC, same reasoning as {@link
 * com.conveyor.common.outbox.OutboxPoller}: multiple orchestrator replicas can run this
 * concurrently and Postgres's row lock is what guarantees no saga is ever double-driven (PLAN.md
 * Phase 6's own exit criterion), with no application-level coordination needed. Recovery on restart
 * is this exact code path, not a separate routine (§7.4's stated design property).
 */
@Component
public class SagaTimeoutSweeper {

  private static final Logger log = LoggerFactory.getLogger(SagaTimeoutSweeper.class);

  private static final String CLAIM_SQL =
      "SELECT id FROM saga_instances WHERE deadline_at IS NOT NULL AND deadline_at < now() "
          + "AND state NOT IN ('COMPLETED', 'ABORTED', 'NEEDS_INTERVENTION') "
          + "ORDER BY deadline_at LIMIT :batchSize FOR UPDATE SKIP LOCKED";

  private final NamedParameterJdbcTemplate jdbcTemplate;
  private final SagaOrchestrationService orchestrationService;

  public SagaTimeoutSweeper(
      NamedParameterJdbcTemplate jdbcTemplate, SagaOrchestrationService orchestrationService) {
    this.jdbcTemplate = jdbcTemplate;
    this.orchestrationService = orchestrationService;
  }

  @Scheduled(fixedDelayString = "${conveyor.saga.sweep-interval:5s}")
  public void sweep() {
    sweepOnce();
  }

  /**
   * Also called directly by tests that disable the scheduled trigger to drive the sweep manually.
   */
  @Transactional
  public int sweepOnce() {
    List<UUID> claimed =
        jdbcTemplate.queryForList(
            CLAIM_SQL, new MapSqlParameterSource("batchSize", 100), UUID.class);
    for (UUID sagaId : claimed) {
      try {
        orchestrationService.applyTimeoutPolicy(sagaId);
      } catch (Exception e) {
        log.error("Timeout policy failed for saga {}; will be reclaimed next sweep", sagaId, e);
      }
    }
    return claimed.size();
  }
}
