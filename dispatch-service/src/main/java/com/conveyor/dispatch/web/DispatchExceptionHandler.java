package com.conveyor.dispatch.web;

import com.conveyor.dispatch.domain.ShipmentNotFoundException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 9457 mappings for dispatch-service's own domain exceptions (ARCHITECTURE.md §10). */
@RestControllerAdvice
public class DispatchExceptionHandler {

  @ExceptionHandler(ShipmentNotFoundException.class)
  public ProblemDetail handleNotFound(ShipmentNotFoundException ex) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    String traceId = MDC.get("traceId");
    if (traceId != null) {
      problem.setProperty("traceId", traceId);
    }
    return problem;
  }
}
