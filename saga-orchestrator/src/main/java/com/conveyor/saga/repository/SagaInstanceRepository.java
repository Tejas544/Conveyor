package com.conveyor.saga.repository;

import com.conveyor.saga.domain.SagaInstance;
import com.conveyor.saga.domain.SagaState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SagaInstanceRepository extends JpaRepository<SagaInstance, UUID> {

  Optional<SagaInstance> findByOrderId(UUID orderId);

  List<SagaInstance> findByState(SagaState state);

  /** "Stuck": non-terminal with a deadline already in the past (PLAN.md's {@code stuck=true}). */
  List<SagaInstance> findByDeadlineAtBefore(Instant now);
}
