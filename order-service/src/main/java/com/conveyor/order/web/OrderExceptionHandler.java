package com.conveyor.order.web;

import com.conveyor.order.domain.IllegalOrderTransitionException;
import com.conveyor.order.domain.OrderNotFoundException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 9457 mappings for order-service's own domain exceptions (ARCHITECTURE.md §10). */
@RestControllerAdvice
public class OrderExceptionHandler {

  @ExceptionHandler(OrderNotFoundException.class)
  public ProblemDetail handleNotFound(OrderNotFoundException ex) {
    return problem(HttpStatus.NOT_FOUND, ex.getMessage());
  }

  @ExceptionHandler(IllegalOrderTransitionException.class)
  public ProblemDetail handleIllegalTransition(IllegalOrderTransitionException ex) {
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
