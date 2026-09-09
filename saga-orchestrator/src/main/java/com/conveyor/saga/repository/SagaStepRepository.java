package com.conveyor.saga.repository;

import com.conveyor.saga.domain.SagaStep;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SagaStepRepository extends JpaRepository<SagaStep, Long> {

  List<SagaStep> findBySagaIdOrderBySeqAsc(UUID sagaId);
}
