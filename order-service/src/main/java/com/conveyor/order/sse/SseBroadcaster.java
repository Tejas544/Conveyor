package com.conveyor.order.sse;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * ADR-10: fans out one Kafka message to every browser connected to this replica. A bounded
 * in-memory ring buffer of recently published events backs {@code Last-Event-ID} resume — a
 * reconnecting client only ever needs what it missed during the reconnect gap, not full history
 * (this replica's own Kafka consumer group already starts from {@code auto.offset.reset=latest}, so
 * there is no earlier history to serve anyway).
 */
@Component
public class SseBroadcaster {

  private static final Logger log = LoggerFactory.getLogger(SseBroadcaster.class);
  private static final int BUFFER_CAPACITY = 500;

  private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
  private final Deque<BufferedEvent> recentEvents = new ArrayDeque<>();
  private final AtomicLong sequence = new AtomicLong();
  private final Object bufferLock = new Object();

  public SseEmitter subscribe(String orderIdFilter, String lastEventId) {
    SseEmitter emitter =
        new SseEmitter(0L); // no timeout; the client controls the connection lifetime
    Subscription subscription = new Subscription(UUID.randomUUID(), emitter, orderIdFilter);
    subscriptions.add(subscription);

    Runnable remove = () -> subscriptions.remove(subscription);
    emitter.onCompletion(remove);
    emitter.onTimeout(remove);
    emitter.onError(t -> remove.run());

    replayMissed(subscription, lastEventId);
    return emitter;
  }

  public void publish(String eventName, String orderId, Object payload) {
    BufferedEvent event =
        new BufferedEvent(sequence.incrementAndGet(), eventName, orderId, payload);
    synchronized (bufferLock) {
      recentEvents.addLast(event);
      while (recentEvents.size() > BUFFER_CAPACITY) {
        recentEvents.removeFirst();
      }
    }
    for (Subscription subscription : subscriptions) {
      send(subscription, event);
    }
  }

  /** ARCHITECTURE.md §10.1: 15s heartbeat frames to defeat idle proxy timeouts. */
  @Scheduled(fixedDelay = 15_000)
  public void broadcastHeartbeat() {
    for (Subscription subscription : subscriptions) {
      try {
        subscription.emitter.send(SseEmitter.event().name("heartbeat").data("{}"));
      } catch (IOException | IllegalStateException e) {
        subscriptions.remove(subscription);
      }
    }
  }

  private void replayMissed(Subscription subscription, String lastEventId) {
    if (lastEventId == null || lastEventId.isBlank()) {
      return;
    }
    long since;
    try {
      since = Long.parseLong(lastEventId);
    } catch (NumberFormatException e) {
      return; // unrecognized id (e.g. from a different replica) -- nothing to replay from here
    }
    List<BufferedEvent> toReplay;
    synchronized (bufferLock) {
      toReplay = recentEvents.stream().filter(e -> e.sequenceId > since).toList();
    }
    for (BufferedEvent event : toReplay) {
      send(subscription, event);
    }
  }

  private void send(Subscription subscription, BufferedEvent event) {
    if (subscription.orderIdFilter != null && !subscription.orderIdFilter.equals(event.orderId)) {
      return;
    }
    try {
      subscription.emitter.send(
          SseEmitter.event()
              .id(Long.toString(event.sequenceId))
              .name(event.eventName)
              .data(event.payload));
    } catch (IOException | IllegalStateException e) {
      log.debug("Dropping dead SSE subscription {}", subscription.id);
      subscriptions.remove(subscription);
    }
  }

  private record Subscription(UUID id, SseEmitter emitter, String orderIdFilter) {
    @Override
    public boolean equals(Object o) {
      return o instanceof Subscription s && Objects.equals(id, s.id);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(id);
    }
  }

  private record BufferedEvent(long sequenceId, String eventName, String orderId, Object payload) {}
}
