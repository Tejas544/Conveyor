package com.conveyor.order.security;

import com.conveyor.order.domain.User;
import com.conveyor.order.repository.UserRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Backs {@code POST /auth/login} and {@code POST /auth/refresh} (ARCHITECTURE.md §10.1, ADR-5). */
@Service
public class AuthService {

  private final UserRepository userRepository;
  private final JwtIssuer jwtIssuer;
  private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

  public AuthService(UserRepository userRepository, JwtIssuer jwtIssuer) {
    this.userRepository = userRepository;
    this.jwtIssuer = jwtIssuer;
  }

  public AuthResult login(String username, String password) {
    User user =
        userRepository
            .findByUsername(username)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Invalid username or password"));
    if (!passwordEncoder.matches(password, user.getPasswordHash())) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
    }
    return issueFor(user);
  }

  /**
   * Re-derives fresh roles from the database rather than trusting anything baked into the refresh
   * token.
   */
  public AuthResult refresh(String refreshToken) {
    String username;
    try {
      username = jwtIssuer.verifyRefresh(refreshToken);
    } catch (JwtIssuer.InvalidRefreshTokenException e) {
      throw new ResponseStatusException(
          HttpStatus.UNAUTHORIZED, "Invalid or expired refresh token", e);
    }
    User user =
        userRepository
            .findByUsername(username)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown user"));
    return issueFor(user);
  }

  private AuthResult issueFor(User user) {
    List<String> bareRoles =
        user.getRoles().stream().map(r -> r.replaceFirst("^ROLE_", "")).toList();
    JwtIssuer.IssuedToken access = jwtIssuer.mintAccessToken(user.getUsername(), bareRoles);
    JwtIssuer.IssuedToken refresh = jwtIssuer.mintRefreshToken(user.getUsername());
    return new AuthResult(access.value(), access.expiresInSeconds(), bareRoles, refresh.value());
  }

  public record AuthResult(
      String accessToken, int expiresInSeconds, List<String> roles, String refreshToken) {}
}
