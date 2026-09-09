package com.conveyor.order;

import com.conveyor.common.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Phase 1 exit criterion (PLAN.md): the Spring context boots against real
 * Postgres/Kafka(Redpanda)/Mongo via Testcontainers.
 */
class OrderServiceApplicationTests extends AbstractIntegrationTest {

  @Test
  void contextLoads() {}
}
