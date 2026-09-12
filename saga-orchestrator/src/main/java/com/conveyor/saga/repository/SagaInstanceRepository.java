package com.conveyor.saga.repository;

import com.conveyor.saga.domain.SagaInstance;
import com.conveyor.saga.domain.SagaState;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SagaInstanceRepository extends JpaRepository<SagaInstance, UUID> {

  Optional<SagaInstance> findByOrderId(UUID orderId);

  /**
   * BUG-0049 (Phase 15, found live under real load): a plain {@code findByOrderId} read here races
   * {@link com.conveyor.saga.scheduling.SagaTimeoutSweeper}'s own {@code SELECT ... FOR UPDATE SKIP
   * LOCKED} claim under Postgres's MVCC — a reply handler could read the pre-timeout state while
   * the sweep's transaction was still in flight (not yet committed), decide the saga was still
   * healthy, and overwrite the sweep's own {@code ABORTED} transition once its own write finally
   * went through after the sweep committed. A blocking {@code PESSIMISTIC_WRITE} lock here makes
   * every reply handler queue up behind an in-flight sweep transaction on the same row (via plain
   * Postgres row locking, not application-level coordination) and always observe the post-commit
   * truth — the existing "saga already ABORTED" branch in each reply handler was already correct,
   * it just never ran because the read was stale.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from SagaInstance s where s.orderId = :orderId")
  Optional<SagaInstance> findByOrderIdForUpdate(@Param("orderId") UUID orderId);

  List<SagaInstance> findByState(SagaState state);

  /** "Stuck": non-terminal with a deadline already in the past (PLAN.md's {@code stuck=true}). */
  List<SagaInstance> findByDeadlineAtBefore(Instant now);
}
