package com.conveyor.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error response, on every service, is RFC 9457 Problem Details ({@code
 * application/problem+json}) with a {@code traceId} property so a dashboard error can link straight
 * to the distributed trace (ARCHITECTURE.md §10, §11). Extending {@link
 * ResponseEntityExceptionHandler} covers Spring MVC's own exceptions (validation, malformed
 * request, etc.) with ProblemDetail bodies already; this class only adds the fallback for anything
 * that reaches the top uncaught, plus the {@code traceId} property on every response, uniformly.
 */
@RestControllerAdvice
public class ProblemDetailAdvice extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ProblemDetailAdvice.class);

  /**
   * ADR-5: a {@code @PreAuthorize} denial is thrown by the method-security AOP interceptor
   * <em>inside</em> {@code DispatcherServlet.doDispatch}, so Spring MVC's own exception-handler
   * chain (this class) sees it before it would ever reach Spring Security's filter-level entry
   * point — without this handler it falls through to {@link #handleUnexpected} as a bare 500.
   */
  @ExceptionHandler(AccessDeniedException.class)
  public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Access is denied.");
    addTraceId(problem);
    return problem;
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail handleUnexpected(Exception ex) {
    log.error("Unhandled exception", ex);
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
    addTraceId(problem);
    return problem;
  }

  /** Package-visible so subclasses/other advices in a service can reuse it. */
  static void addTraceId(ProblemDetail problem) {
    String traceId = MDC.get("traceId");
    if (traceId != null) {
      problem.setProperty("traceId", traceId);
    }
  }
}
