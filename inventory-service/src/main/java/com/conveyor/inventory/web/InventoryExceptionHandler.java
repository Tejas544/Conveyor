package com.conveyor.inventory.web;

import com.conveyor.inventory.domain.SkuNotFoundException;
import com.conveyor.inventory.domain.StockAdjustmentRejectedException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 9457 mappings for inventory-service's own domain exceptions (ARCHITECTURE.md §10). */
@RestControllerAdvice
public class InventoryExceptionHandler {

  @ExceptionHandler(SkuNotFoundException.class)
  public ProblemDetail handleNotFound(SkuNotFoundException ex) {
    return problem(HttpStatus.NOT_FOUND, ex.getMessage());
  }

  @ExceptionHandler(StockAdjustmentRejectedException.class)
  public ProblemDetail handleRejected(StockAdjustmentRejectedException ex) {
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
