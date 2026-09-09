package com.conveyor.common.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * ARCHITECTURE.md §12, PLAN.md Phase 2: proves {@code infra/postgres/init-service-databases.sh} —
 * the same script docker-compose runs — actually grants {@code conveyor_verifier} SELECT-only
 * privileges, exercised against a real Postgres container rather than trusted by inspection.
 */
@Testcontainers
class VerifierRolePrivilegeTest {

  private static final String DB_NAME = "order_service";
  private static final String DB_USER = "order_service";
  private static final String DB_PASSWORD = "order_service_local_dev_only";
  private static final String VERIFIER_USER = "conveyor_verifier";
  private static final String VERIFIER_PASSWORD = "verifier_local_dev_only";

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
          .withUsername("postgres")
          .withPassword("postgres_local_dev_only")
          .withDatabaseName("postgres")
          .withEnv("ORDER_DB_NAME", DB_NAME)
          .withEnv("ORDER_DB_USER", DB_USER)
          .withEnv("ORDER_DB_PASSWORD", DB_PASSWORD)
          .withEnv("INVENTORY_DB_NAME", "inventory_service")
          .withEnv("INVENTORY_DB_USER", "inventory_service")
          .withEnv("INVENTORY_DB_PASSWORD", "inventory_service_local_dev_only")
          .withEnv("PAYMENT_DB_NAME", "payment_service")
          .withEnv("PAYMENT_DB_USER", "payment_service")
          .withEnv("PAYMENT_DB_PASSWORD", "payment_service_local_dev_only")
          .withEnv("SAGA_DB_NAME", "saga_orchestrator")
          .withEnv("SAGA_DB_USER", "saga_orchestrator")
          .withEnv("SAGA_DB_PASSWORD", "saga_orchestrator_local_dev_only")
          .withEnv("DISPATCH_DB_NAME", "dispatch_service")
          .withEnv("DISPATCH_DB_USER", "dispatch_service")
          .withEnv("DISPATCH_DB_PASSWORD", "dispatch_service_local_dev_only")
          .withEnv("VERIFIER_DB_USER", VERIFIER_USER)
          .withEnv("VERIFIER_DB_PASSWORD", VERIFIER_PASSWORD)
          .withCopyFileToContainer(
              MountableFile.forHostPath("../infra/postgres/init-service-databases.sh"),
              "/docker-entrypoint-initdb.d/init-service-databases.sh");

  @Test
  void verifierCanSelectButNotWrite() throws SQLException {
    String orderDbJdbcUrl =
        POSTGRES.getJdbcUrl().replaceFirst("/postgres(\\?|$)", "/" + DB_NAME + "$1");

    // The owning role creates a table, matching what a Flyway migration does at service startup —
    // default privileges granted at db-creation time should apply to it automatically.
    try (Connection ownerConn = DriverManager.getConnection(orderDbJdbcUrl, DB_USER, DB_PASSWORD);
        Statement stmt = ownerConn.createStatement()) {
      stmt.execute("create table probe (id int primary key)");
      stmt.execute("insert into probe (id) values (1)");
    }

    try (Connection verifierConn =
        DriverManager.getConnection(orderDbJdbcUrl, VERIFIER_USER, VERIFIER_PASSWORD)) {
      try (Statement stmt = verifierConn.createStatement();
          ResultSet rs = stmt.executeQuery("select id from probe")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getInt("id")).isEqualTo(1);
      }

      try (Statement stmt = verifierConn.createStatement()) {
        assertThatThrownBy(() -> stmt.execute("insert into probe (id) values (2)"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("permission denied");
      }

      try (Statement stmt = verifierConn.createStatement()) {
        assertThatThrownBy(() -> stmt.execute("update probe set id = 9 where id = 1"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("permission denied");
      }

      try (Statement stmt = verifierConn.createStatement()) {
        assertThatThrownBy(() -> stmt.execute("delete from probe where id = 1"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("permission denied");
      }
    }
  }
}
