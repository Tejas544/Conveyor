package com.conveyor.inventory.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.conveyor.common.testsupport.AbstractIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** PLAN.md Phase 2: Flyway migrates a clean database to head with no checksum drift. */
class FlywayMigrationTest extends AbstractIntegrationTest {

  @Autowired private Flyway flyway;

  @Test
  void migratesToHeadWithNoChecksumDrift() {
    flyway.validate();
    assertThat(flyway.info().applied()).isNotEmpty();
    assertThat(flyway.info().pending()).isEmpty();
  }
}
