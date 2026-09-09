package com.conveyor.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** ARCHITECTURE.md §5.1, ADR-5. {@code roles} holds {@code ROLE_OPS} / {@code ROLE_ADMIN}. */
@Entity
@Table(name = "users")
public class User {

  @Id private UUID id;

  @Column(nullable = false, unique = true)
  private String username;

  @Column(name = "password_hash", nullable = false)
  private String passwordHash;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(nullable = false)
  private List<String> roles;

  protected User() {}

  public User(UUID id, String username, String passwordHash, List<String> roles) {
    this.id = id;
    this.username = username;
    this.passwordHash = passwordHash;
    this.roles = roles;
  }

  public UUID getId() {
    return id;
  }

  public String getUsername() {
    return username;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public List<String> getRoles() {
    return roles;
  }
}
