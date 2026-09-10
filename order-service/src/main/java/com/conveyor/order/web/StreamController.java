package com.conveyor.order.web;

import com.conveyor.order.sse.SseBroadcaster;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * ARCHITECTURE.md §10.1, ADR-5: authenticated (the SSE stream is explicitly in scope of "everything
 * else" requires auth). The browser's native {@code EventSource} can't send an {@code
 * Authorization} header, so the frontend deliberately does not use it here — see ADR-5's Javadoc
 * note — and instead consumes this endpoint with {@code fetch} + a manual SSE frame parser, which
 * can set the header like any other request.
 */
@RestController
@RequestMapping("/api/v1/stream")
@Tag(name = "Stream")
public class StreamController {

  private final SseBroadcaster broadcaster;

  public StreamController(SseBroadcaster broadcaster) {
    this.broadcaster = broadcaster;
  }

  @GetMapping("/orders")
  @PreAuthorize("hasRole('OPS')")
  @Operation(
      summary = "Live order/saga/shipment events (SSE).",
      description =
          "Optional ?orderId= narrows the stream to one order's timeline. Supports Last-Event-ID resume.")
  public SseEmitter streamOrders(
      @RequestParam(required = false) String orderId,
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
    return broadcaster.subscribe(orderId, lastEventId);
  }
}
