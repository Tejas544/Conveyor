package com.conveyor.order.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.order.domain.User;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

class UserRepositoryIntegrationTest extends AbstractIntegrationTest {

  @Autowired private UserRepository userRepository;

  @Test
  @Transactional
  void savesAndFindsByUsername() {
    userRepository.saveAndFlush(
        new User(UUID.randomUUID(), "ops-user-1", "bcrypt-hash", List.of("ROLE_OPS")));

    User found = userRepository.findByUsername("ops-user-1").orElseThrow();
    assertThat(found.getRoles()).containsExactly("ROLE_OPS");
  }

  @Test
  @Transactional
  void usernameIsUnique() {
    userRepository.saveAndFlush(
        new User(UUID.randomUUID(), "dup-user", "hash-a", List.of("ROLE_OPS")));

    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            userRepository.saveAndFlush(
                new User(UUID.randomUUID(), "dup-user", "hash-b", List.of("ROLE_ADMIN"))));
  }
}
