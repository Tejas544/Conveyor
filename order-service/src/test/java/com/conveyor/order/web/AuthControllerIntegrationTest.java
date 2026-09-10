package com.conveyor.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.conveyor.common.testsupport.AbstractIntegrationTest;
import com.conveyor.order.domain.User;
import com.conveyor.order.repository.UserRepository;
import com.conveyor.order.web.dto.AuthResponse;
import com.conveyor.order.web.dto.LoginRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * PLAN.md Phase 8 / ADR-5: {@code POST /auth/login} issues a real access token plus an {@code
 * HttpOnly} refresh cookie; {@code POST /auth/refresh} exchanges that cookie for a fresh access
 * token without re-sending credentials; {@code POST /auth/logout} clears it.
 */
class AuthControllerIntegrationTest extends AbstractIntegrationTest {

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private UserRepository userRepository;

  private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

  @BeforeEach
  void seedUser() {
    if (userRepository.findByUsername("auth-test-ops").isEmpty()) {
      userRepository.save(
          new User(
              UUID.randomUUID(),
              "auth-test-ops",
              passwordEncoder.encode("correct-horse-battery-staple"),
              List.of("ROLE_OPS")));
    }
  }

  @Test
  void correctCredentialsIssueAnAccessTokenAndARefreshCookie() {
    ResponseEntity<AuthResponse> response =
        restTemplate.postForEntity(
            "/api/v1/auth/login",
            new LoginRequest("auth-test-ops", "correct-horse-battery-staple"),
            AuthResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().accessToken()).isNotBlank();
    assertThat(response.getBody().tokenType()).isEqualTo("Bearer");
    assertThat(response.getBody().roles()).containsExactly("OPS");

    String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
    assertThat(setCookie)
        .contains("refreshToken=")
        .contains("HttpOnly")
        .contains("Path=/api/v1/auth");
  }

  @Test
  void wrongPasswordIsRejected() {
    ResponseEntity<String> response =
        restTemplate.postForEntity(
            "/api/v1/auth/login",
            new LoginRequest("auth-test-ops", "wrong-password"),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void unknownUsernameIsRejected() {
    ResponseEntity<String> response =
        restTemplate.postForEntity(
            "/api/v1/auth/login", new LoginRequest("nobody-such-user", "whatever"), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void refreshCookieExchangesForANewAccessTokenWithoutResendingCredentials() {
    ResponseEntity<AuthResponse> login =
        restTemplate.postForEntity(
            "/api/v1/auth/login",
            new LoginRequest("auth-test-ops", "correct-horse-battery-staple"),
            AuthResponse.class);
    String refreshCookie = firstCookiePair(login);

    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.COOKIE, refreshCookie);
    ResponseEntity<AuthResponse> refreshed =
        restTemplate.postForEntity(
            "/api/v1/auth/refresh", new HttpEntity<>(null, headers), AuthResponse.class);

    assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(refreshed.getBody().accessToken()).isNotBlank();
    assertThat(refreshed.getBody().accessToken()).isNotEqualTo(login.getBody().accessToken());
    assertThat(refreshed.getBody().roles()).containsExactly("OPS");
  }

  @Test
  void refreshWithoutACookieIsRejected() {
    ResponseEntity<String> response =
        restTemplate.postForEntity("/api/v1/auth/refresh", new HttpEntity<>(null), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void logoutClearsTheRefreshCookie() {
    ResponseEntity<Void> response =
        restTemplate.postForEntity("/api/v1/auth/logout", new HttpEntity<>(null), Void.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
    assertThat(setCookie).contains("refreshToken=").contains("Max-Age=0");
  }

  private static String firstCookiePair(ResponseEntity<?> response) {
    String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
    return setCookie.split(";", 2)[0];
  }
}
