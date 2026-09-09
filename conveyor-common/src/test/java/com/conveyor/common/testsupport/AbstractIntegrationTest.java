package com.conveyor.common.testsupport;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared base for every service's integration tests: real Postgres, real Redpanda (ADR-2 — the
 * default local/test broker, not Apache Kafka), real MongoDB, via Testcontainers. Published as
 * conveyor-common's test-jar so this is written once, not once per service.
 *
 * <p>A service that does not use Mongo simply never wires {@code spring.data.mongodb.uri} into
 * anything — the container still starts, which is a modest, accepted cost in exchange for one base
 * class instead of a family of them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

  @Container
  protected static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

  @Container
  protected static final RedpandaContainer REDPANDA =
      new RedpandaContainer(
          DockerImageName.parse("docker.redpanda.com/redpandadata/redpanda:v24.2.7"));

  @Container
  protected static final MongoDBContainer MONGO =
      new MongoDBContainer(DockerImageName.parse("mongo:7"));

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.kafka.bootstrap-servers", REDPANDA::getBootstrapServers);
    registry.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
  }
}
