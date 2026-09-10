package com.conveyor.order.web;

import com.conveyor.order.security.AuthService;
import com.conveyor.order.security.AuthService.AuthResult;
import com.conveyor.order.web.dto.AuthResponse;
import com.conveyor.order.web.dto.LoginRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * ARCHITECTURE.md §10.1, ADR-5: order-service is the sole token issuer. The access token travels in
 * the response body (the frontend holds it in memory and sends it as {@code Authorization:
 * Bearer}); the refresh token never touches JavaScript — it is set as an {@code HttpOnly} cookie
 * scoped to this controller's own path, exactly so a successful XSS can't read it.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth")
public class AuthController {

  private static final String REFRESH_COOKIE = "refreshToken";
  private static final String COOKIE_PATH = "/api/v1/auth";

  private final AuthService authService;
  private final com.conveyor.order.security.JwtIssuer jwtIssuer;

  public AuthController(AuthService authService, com.conveyor.order.security.JwtIssuer jwtIssuer) {
    this.authService = authService;
    this.jwtIssuer = jwtIssuer;
  }

  @PostMapping("/login")
  @Operation(summary = "Exchange username/password for an access token; sets the refresh cookie.")
  public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
    AuthResult result = authService.login(request.username(), request.password());
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, refreshCookie(result.refreshToken()).toString())
        .body(AuthResponse.from(result));
  }

  @PostMapping("/refresh")
  @Operation(summary = "Exchange the refresh cookie for a new access token.")
  public ResponseEntity<AuthResponse> refresh(
      @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
    if (refreshToken == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No refresh cookie present");
    }
    AuthResult result = authService.refresh(refreshToken);
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, refreshCookie(result.refreshToken()).toString())
        .body(AuthResponse.from(result));
  }

  @PostMapping("/logout")
  @Operation(
      summary =
          "Clears the refresh cookie. Stateless -- any previously issued access token remains valid until it expires.")
  public ResponseEntity<Void> logout() {
    ResponseCookie cleared =
        ResponseCookie.from(REFRESH_COOKIE, "")
            .httpOnly(true)
            .secure(jwtIssuer.cookieSecure())
            .sameSite("Strict")
            .path(COOKIE_PATH)
            .maxAge(0)
            .build();
    return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cleared.toString()).build();
  }

  private ResponseCookie refreshCookie(String value) {
    return ResponseCookie.from(REFRESH_COOKIE, value)
        .httpOnly(true)
        .secure(jwtIssuer.cookieSecure())
        .sameSite("Strict")
        .path(COOKIE_PATH)
        .maxAge(jwtIssuer.refreshTokenTtlSeconds())
        .build();
  }
}
