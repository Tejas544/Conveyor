package com.conveyor.verifier.engine;

import com.conveyor.verifier.config.ServiceClients;
import com.conveyor.verifier.config.ServiceDatabases;
import com.conveyor.verifier.domain.CheckMode;
import com.conveyor.verifier.invariants.Invariant;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Runs the whole catalogue (all beans implementing {@link Invariant} — Spring collects them, so
 * adding a 16th invariant is "write the class," nothing else) in either mode.
 */
@Component
public class VerificationEngine {

  private final List<Invariant> catalogue;
  private final ServiceDatabases databases;
  private final ServiceClients clients;

  public VerificationEngine(
      List<Invariant> catalogue, ServiceDatabases databases, ServiceClients clients) {
    this.catalogue = catalogue.stream().sorted(Comparator.comparing(i -> i.id().tag())).toList();
    this.databases = databases;
    this.clients = clients;
  }

  public RunReport runInsideOut() {
    return new RunReport(
        Instant.now(),
        CheckMode.INSIDE_OUT,
        catalogue.stream().map(i -> i.checkInsideOut(databases)).toList());
  }

  public RunReport runOutsideIn() {
    return new RunReport(
        Instant.now(),
        CheckMode.OUTSIDE_IN,
        catalogue.stream().map(i -> i.checkOutsideIn(clients)).toList());
  }

  public int catalogueSize() {
    return catalogue.size();
  }
}
