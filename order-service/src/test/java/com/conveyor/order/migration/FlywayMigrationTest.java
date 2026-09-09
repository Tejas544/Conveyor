package com.conveyor.order.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.conveyor.common.testsupport.AbstractIntegrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * PLAN.md Phase 2: Flyway migrates a clean database to head, and {@code flyway validate} passes (no
 * checksum drift). The context is already booted against a fresh Testcontainers Postgres by {@link
 * AbstractIntegrationTest}, so if the migrations hadn't already applied cleanly the context would
 * not have started — this test asserts that state explicitly rather than relying on that as an
 * implicit side effect.
 */
class FlywayMigrationTest extends AbstractIntegrationTest {

  @Autowired private Flyway flyway;

  @Test
  void migratesToHeadWithNoChecksumDrift() {
    flyway.validate();
    MigrationInfo[] applied = flyway.info().applied();
    assertThat(applied).isNotEmpty();
    assertThat(flyway.info().pending()).isEmpty();
  }
}
