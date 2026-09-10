package com.conveyor.payment.web;

import com.conveyor.payment.domain.PaymentNotFoundException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 9457 mappings for payment-service's own domain exceptions (ARCHITECTURE.md §10). */
@RestControllerAdvice
public class PaymentExceptionHandler {

  @ExceptionHandler(PaymentNotFoundException.class)
  public ProblemDetail handleNotFound(PaymentNotFoundException ex) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    String traceId = MDC.get("traceId");
    if (traceId != null) {
      problem.setProperty("traceId", traceId);
    }
    return problem;
  }
}
