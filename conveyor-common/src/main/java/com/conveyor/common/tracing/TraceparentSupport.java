package com.conveyor.common.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

/**
 * ARCHITECTURE.md §11 / ADR-7: the transactional outbox deliberately decouples "commit the business
 * write" from "publish to Kafka" onto a later, unrelated poll of {@link
 * com.conveyor.common.outbox.OutboxPoller} — a different thread with no span of its own. Without
 * this, every outbox-published message would start a brand-new, disconnected trace instead of
 * continuing the one that actually caused it, which would silently defeat Phase 9's "one order is
 * one trace across five services" exit criterion. The fix is to capture the *current* span's W3C
 * {@code traceparent} at outbox-row-write time — same transaction as the business write — and carry
 * it as a stored header; {@link com.conveyor.common.outbox.OutboxPoller} copies stored headers onto
 * the outgoing {@code ProducerRecord} unchanged, and the consumer side's Kafka listener observation
 * extracts it exactly as it would a live-injected one.
 *
 * <p>Registered by {@link TracingAutoConfiguration} rather than {@code @Component}: this class
 * lives in {@code com.conveyor.common}, outside every service's own {@code @SpringBootApplication}
 * package tree, so plain component scanning would never find it — the same reason every other
 * conveyor-common bean in this module is wired through an {@code @AutoConfiguration}.
 */
public class TraceparentSupport {

  private final Tracer tracer;

  public TraceparentSupport(Tracer tracer) {
    this.tracer = tracer;
  }

  /**
   * {@code null} if there is no current span — e.g. a scheduler-initiated write (timeout sweep)
   * with no inbound request or message to inherit a trace from.
   */
  public String currentTraceparent() {
    Span current = tracer.currentSpan();
    if (current == null) {
      return null;
    }
    String flag = Boolean.TRUE.equals(current.context().sampled()) ? "01" : "00";
    return "00-" + current.context().traceId() + "-" + current.context().spanId() + "-" + flag;
  }
}
