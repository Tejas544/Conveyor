package com.conveyor.saga.web;

import com.conveyor.saga.domain.SagaInstance;
import com.conveyor.saga.domain.SagaNotFoundException;
import com.conveyor.saga.domain.SagaState;
import com.conveyor.saga.repository.SagaInstanceRepository;
import com.conveyor.saga.repository.SagaStepRepository;
import com.conveyor.saga.service.SagaOrchestrationService;
import com.conveyor.saga.web.dto.SagaDetailResponse;
import com.conveyor.saga.web.dto.SagaSummaryResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** ARCHITECTURE.md §10.2. */
@RestController
@RequestMapping("/api/v1/sagas")
public class SagaController {

  private final SagaInstanceRepository sagaInstanceRepository;
  private final SagaStepRepository sagaStepRepository;
  private final SagaOrchestrationService orchestrationService;

  public SagaController(
      SagaInstanceRepository sagaInstanceRepository,
      SagaStepRepository sagaStepRepository,
      SagaOrchestrationService orchestrationService) {
    this.sagaInstanceRepository = sagaInstanceRepository;
    this.sagaStepRepository = sagaStepRepository;
    this.orchestrationService = orchestrationService;
  }

  @GetMapping("/{orderId}")
  public SagaDetailResponse getByOrderId(@PathVariable UUID orderId) {
    SagaInstance saga =
        sagaInstanceRepository
            .findByOrderId(orderId)
            .orElseThrow(() -> SagaNotFoundException.forOrderId(orderId));
    return SagaDetailResponse.from(
        saga, sagaStepRepository.findBySagaIdOrderBySeqAsc(saga.getId()));
  }

  @GetMapping
  public List<SagaSummaryResponse> list(
      @RequestParam(required = false) SagaState state,
      @RequestParam(required = false, defaultValue = "false") boolean stuck) {
    List<SagaInstance> sagas;
    if (stuck) {
      sagas = sagaInstanceRepository.findByDeadlineAtBefore(Instant.now());
    } else if (state != null) {
      sagas = sagaInstanceRepository.findByState(state);
    } else {
      sagas = sagaInstanceRepository.findAll();
    }
    return sagas.stream().map(SagaSummaryResponse::from).toList();
  }

  @PostMapping("/{sagaId}/retry")
  @PreAuthorize("hasRole('ADMIN')")
  public SagaDetailResponse retry(@PathVariable UUID sagaId) {
    SagaInstance saga = orchestrationService.retrySaga(sagaId);
    return SagaDetailResponse.from(
        saga, sagaStepRepository.findBySagaIdOrderBySeqAsc(saga.getId()));
  }

  @PostMapping("/{sagaId}/abort")
  @PreAuthorize("hasRole('ADMIN')")
  public SagaDetailResponse abort(
      @PathVariable UUID sagaId,
      @RequestParam(defaultValue = "ABORTED_BY_OPERATOR") String reason) {
    SagaInstance saga = orchestrationService.abortSaga(sagaId, reason);
    return SagaDetailResponse.from(
        saga, sagaStepRepository.findBySagaIdOrderBySeqAsc(saga.getId()));
  }
}
