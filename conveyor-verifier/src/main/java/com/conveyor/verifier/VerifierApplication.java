package com.conveyor.verifier;

import com.conveyor.common.outbox.OutboxAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * conveyor-verifier is the one service in this codebase with no single "own" database — it has
 * five, via {@link com.conveyor.verifier.config.ServiceDatabases}'s own manually-built {@code
 * HikariDataSource}s, none of them "the" datasource — so it deliberately never sets {@code
 * spring.datasource.url}. Spring Boot's own {@code DataSourceAutoConfiguration} (and the
 * transaction-manager/JdbcTemplate auto-configurations layered on it) still activate by default
 * whenever {@code DataSource} is on the classpath, regardless of whether anything explicitly
 * autowires the bean it produces, and fail outright without a URL — excluded here, not worked
 * around with a dummy URL. {@code OutboxAutoConfiguration} is excluded for the same shape of reason
 * (it activates whenever both {@code DataSource} and {@code KafkaTemplate} are on the classpath —
 * conveyor-verifier gets both transitively via conveyor-common, see BUG-0022 in BUGS.md — and this
 * service never writes anything, so it was never supposed to have an outbox poller either).
 */
@SpringBootApplication(
    exclude = {
      OutboxAutoConfiguration.class,
      DataSourceAutoConfiguration.class,
      DataSourceTransactionManagerAutoConfiguration.class,
      JdbcTemplateAutoConfiguration.class
    })
@ConfigurationPropertiesScan
@EnableScheduling
public class VerifierApplication {

  public static void main(String[] args) {
    SpringApplication.run(VerifierApplication.class, args);
  }
}
