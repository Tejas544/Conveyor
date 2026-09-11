package com.conveyor.verifier.testsupport;

import com.conveyor.verifier.config.ServiceDatabases;
import com.conveyor.verifier.config.VerifierProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * One Testcontainers Postgres instance, five databases created inside it (mirroring {@code
 * infra/postgres/init-service-databases.sh}'s real topology — ARCHITECTURE.md §4's "one Postgres
 * instance, one database + role per service" — minus the role/grant layer, since these tests
 * exercise the checker's SQL, not the {@code conveyor_verifier} role's privilege boundary, which
 * Phase 2's own verifier-role privilege test already covers), each loaded with the real per-service
 * baseline DDL copied verbatim into {@code src/test/resources/schema/*.sql} — a deliberate
 * simplification recorded in CONTEXT.md's Key Decisions Log rather than pulling all five service
 * modules in as test dependencies (which would create a reactor coupling this module has no other
 * reason to have, purely to reuse Flyway resources five other modules already own).
 */
@Testcontainers
public abstract class MultiDatabaseTestSupport {

  private static final String ADMIN_DB = "postgres";

  @Container
  protected static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
          .withStartupTimeout(Duration.ofMinutes(2));

  protected static ServiceDatabases serviceDatabases;
  protected static VerifierProperties testProperties;

  static {
    POSTGRES.start();
    createDatabaseAndLoadSchema("order_service", "schema/order.sql");
    createDatabaseAndLoadSchema("inventory_service", "schema/inventory.sql");
    createDatabaseAndLoadSchema("payment_service", "schema/payment.sql");
    createDatabaseAndLoadSchema("saga_orchestrator", "schema/saga.sql");
    createDatabaseAndLoadSchema("dispatch_service", "schema/dispatch.sql");

    VerifierProperties.Postgres postgres =
        new VerifierProperties.Postgres(
            POSTGRES.getHost(),
            POSTGRES.getMappedPort(5432),
            POSTGRES.getUsername(),
            POSTGRES.getPassword(),
            "order_service",
            "inventory_service",
            "payment_service",
            "saga_orchestrator",
            "dispatch_service");
    testProperties =
        new VerifierProperties(
            postgres,
            null,
            Duration.ofSeconds(10),
            Duration.ofMinutes(5),
            Duration.ofSeconds(60),
            Duration.ofSeconds(30),
            null);
    serviceDatabases = new ServiceDatabases(testProperties);
  }

  private static void createDatabaseAndLoadSchema(String database, String schemaResourcePath) {
    try (Connection admin = adminConnection(ADMIN_DB);
        Statement statement = admin.createStatement()) {
      statement.execute("create database " + database);
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to create database " + database, e);
    }

    String ddl;
    try {
      ddl =
          StreamUtils.copyToString(
              new ClassPathResource(schemaResourcePath).getInputStream(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    try (Connection connection = adminConnection(database);
        Statement statement = connection.createStatement()) {
      statement.execute(ddl);
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to load schema into " + database, e);
    }
  }

  private static Connection adminConnection(String database) throws SQLException {
    String url =
        "jdbc:postgresql://%s:%d/%s"
            .formatted(POSTGRES.getHost(), POSTGRES.getMappedPort(5432), database);
    return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
