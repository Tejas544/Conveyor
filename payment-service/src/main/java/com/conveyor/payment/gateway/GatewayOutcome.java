package com.conveyor.payment.gateway;

/** ARCHITECTURE.md §3.3: what {@link MockPaymentGateway#charge()} can return. */
public sealed interface GatewayOutcome {

  record Captured(String gatewayReference) implements GatewayOutcome {}

  record Declined() implements GatewayOutcome {}

  record TimedOut() implements GatewayOutcome {}

  record GatewayError() implements GatewayOutcome {}
}
