package com.conveyor.common.kafka;

/**
 * Topic name constants (ARCHITECTURE.md §6.2). Convention: {@code
 * conveyor.<context>.<kind>.v<major>}, kind ∈ {commands, replies, events}. The message key is
 * always {@code orderId} everywhere, so every message for one order lands on one partition.
 */
public final class KafkaTopics {

  public static final String ORDER_EVENTS = "conveyor.order.events.v1";
  public static final String INVENTORY_COMMANDS = "conveyor.inventory.commands.v1";
  public static final String PAYMENT_COMMANDS = "conveyor.payment.commands.v1";
  public static final String SAGA_REPLIES = "conveyor.saga.replies.v1";
  public static final String DISPATCH_EVENTS = "conveyor.dispatch.events.v1";

  public static final int PARTITIONS = 6;
  public static final short LOCAL_REPLICATION_FACTOR = 1;

  public static String deadLetterTopic(String topic) {
    return topic + ".dlq";
  }

  private KafkaTopics() {}
}
