package com.conveyor.saga.web;

import com.conveyor.saga.domain.IllegalSagaStateException;
import com.conveyor.saga.domain.SagaNotFoundException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 9457 mappings for saga-orchestrator's own domain exceptions (ARCHITECTURE.md §10.2). */
@RestControllerAdvice
public class SagaExceptionHandler {

  @ExceptionHandler(SagaNotFoundException.class)
  public ProblemDetail handleNotFound(SagaNotFoundException ex) {
    return problem(HttpStatus.NOT_FOUND, ex.getMessage());
  }

  @ExceptionHandler(IllegalSagaStateException.class)
  public ProblemDetail handleIllegalState(IllegalSagaStateException ex) {
    return problem(HttpStatus.CONFLICT, ex.getMessage());
  }

  private ProblemDetail problem(HttpStatus status, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    String traceId = MDC.get("traceId");
    if (traceId != null) {
      problem.setProperty("traceId", traceId);
    }
    return problem;
  }
}
