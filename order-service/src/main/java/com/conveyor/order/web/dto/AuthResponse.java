package com.conveyor.order.web.dto;

import com.conveyor.order.security.AuthService.AuthResult;
import java.util.List;

public record AuthResponse(
    String accessToken, String tokenType, int expiresIn, List<String> roles) {

  public static AuthResponse from(AuthResult result) {
    return new AuthResponse(
        result.accessToken(), "Bearer", result.expiresInSeconds(), result.roles());
  }
}
