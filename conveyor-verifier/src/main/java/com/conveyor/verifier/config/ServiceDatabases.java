package com.conveyor.verifier.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Five {@link JdbcTemplate}s, one per service database, all authenticated as the read-only {@code
 * conveyor_verifier} role (ARCHITECTURE.md §12) — never a service's own role. Deliberately plain
 * JDBC, not JPA: the checker runs cross-table aggregate SQL no entity graph maps cleanly onto, and
 * it never writes anything, so an entity layer would buy nothing.
 *
 * <p>Keyed by the same short names used throughout this module: {@code order}, {@code inventory},
 * {@code payment}, {@code saga}, {@code dispatch}.
 */
@Configuration
public class ServiceDatabases implements DisposableBean {

  private final Map<String, HikariDataSource> dataSources = new LinkedHashMap<>();
  private final Map<String, JdbcTemplate> jdbcTemplates = new LinkedHashMap<>();

  public ServiceDatabases(VerifierProperties properties) {
    VerifierProperties.Postgres pg = properties.postgres();
    register("order", pg, pg.orderDb());
    register("inventory", pg, pg.inventoryDb());
    register("payment", pg, pg.paymentDb());
    register("saga", pg, pg.sagaDb());
    register("dispatch", pg, pg.dispatchDb());
  }

  private void register(String key, VerifierProperties.Postgres pg, String database) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl("jdbc:postgresql://%s:%d/%s".formatted(pg.host(), pg.port(), database));
    config.setUsername(pg.username());
    config.setPassword(pg.password());
    config.setReadOnly(true);
    config.setMaximumPoolSize(2);
    config.setPoolName("verifier-" + key);
    HikariDataSource dataSource = new HikariDataSource(config);
    dataSources.put(key, dataSource);
    jdbcTemplates.put(key, new JdbcTemplate(dataSource));
  }

  public JdbcTemplate get(String key) {
    JdbcTemplate template = jdbcTemplates.get(key);
    if (template == null) {
      throw new IllegalArgumentException("No such service database: " + key);
    }
    return template;
  }

  @Bean
  public Map<String, JdbcTemplate> serviceJdbcTemplates() {
    return jdbcTemplates;
  }

  @Override
  public void destroy() {
    dataSources.values().forEach(HikariDataSource::close);
  }
}
