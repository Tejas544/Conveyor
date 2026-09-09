package com.conveyor.order.seed;

import com.conveyor.order.domain.User;
import com.conveyor.order.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * {@code make seed} (PLAN.md Phase 2): creates the demo {@code ops}/{@code admin} accounts ADR-5
 * needs once auth lands in Phase 3+. Idempotent — a username already present is left alone. Runs
 * under {@code SPRING_PROFILES_ACTIVE=seed} only, via {@code docker compose run --rm}, and exits
 * the JVM once done rather than staying up as a web server.
 */
@Component
@Profile("seed")
public class UserSeedRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(UserSeedRunner.class);

  private final UserRepository userRepository;
  private final ConfigurableApplicationContext context;
  private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

  private final String opsUsername;
  private final String opsPassword;
  private final String adminUsername;
  private final String adminPassword;

  public UserSeedRunner(
      UserRepository userRepository,
      ConfigurableApplicationContext context,
      org.springframework.core.env.Environment env) {
    this.userRepository = userRepository;
    this.context = context;
    this.opsUsername = env.getProperty("seed.ops.username", "ops");
    this.opsPassword = env.getProperty("seed.ops.password", "ops_local_dev_only");
    this.adminUsername = env.getProperty("seed.admin.username", "admin");
    this.adminPassword = env.getProperty("seed.admin.password", "admin_local_dev_only");
  }

  @Override
  public void run(ApplicationArguments args) {
    seedUser(opsUsername, opsPassword, List.of("ROLE_OPS"));
    seedUser(adminUsername, adminPassword, List.of("ROLE_OPS", "ROLE_ADMIN"));
    log.info("User seed complete.");
    System.exit(SpringApplication.exit(context, () -> 0));
  }

  private void seedUser(String username, String rawPassword, List<String> roles) {
    if (userRepository.findByUsername(username).isPresent()) {
      log.info("Seed user '{}' already exists, skipping.", username);
      return;
    }
    userRepository.save(
        new User(UUID.randomUUID(), username, passwordEncoder.encode(rawPassword), roles));
    log.info("Seeded user '{}'.", username);
  }
}
