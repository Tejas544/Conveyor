package com.conveyor.order.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * ADR-5: order-service is the sole token issuer. Mints the same RS256 shape {@code TestJwtSupport}
 * mints for tests (subject + bare-name {@code roles} claim, no {@code ROLE_} prefix — {@link
 * com.conveyor.common.security.SecurityAutoConfiguration}'s {@code JwtGrantedAuthoritiesConverter}
 * adds that prefix itself), plus a separate refresh-token shape distinguished by {@code
 * "tokenType": "refresh"} and carrying no roles — deliberately stateless (no server-side refresh
 * store) since the only claim {@link #verifyRefresh} trusts from it is the subject, and every
 * refresh re-reads the user's current roles from the database rather than trusting stale ones baked
 * into the token.
 */
@Component
@EnableConfigurationProperties(JwtIssuerProperties.class)
public class JwtIssuer {

  private final RSAPrivateKey privateKey;
  private final RSAPublicKey publicKey;
  private final JwtIssuerProperties properties;

  public JwtIssuer(JwtIssuerProperties properties) throws Exception {
    this.properties = properties;
    byte[] pkcs8 = Base64.getDecoder().decode(properties.privateKeyPem());
    this.privateKey =
        (RSAPrivateKey)
            KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
    // Derived from the same modulus/exponent as the private key, purely to verify refresh tokens
    // this same issuer minted — this is not conveyor-common's public-key trust anchor, just a
    // local self-check.
    java.security.interfaces.RSAPrivateCrtKey crt =
        (java.security.interfaces.RSAPrivateCrtKey) privateKey;
    java.security.spec.RSAPublicKeySpec pubSpec =
        new java.security.spec.RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent());
    this.publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(pubSpec);
  }

  public IssuedToken mintAccessToken(String subject, List<String> roles) {
    Instant now = Instant.now();
    Instant expiry = now.plus(Duration.ofMinutes(properties.accessTokenTtlMinutes()));
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .subject(subject)
            .claim("roles", roles)
            .jwtID(UUID.randomUUID().toString())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(expiry))
            .build();
    return new IssuedToken(sign(claims), expiry, (int) Duration.between(now, expiry).getSeconds());
  }

  public IssuedToken mintRefreshToken(String subject) {
    Instant now = Instant.now();
    Instant expiry = now.plus(Duration.ofDays(properties.refreshTokenTtlDays()));
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .subject(subject)
            .claim("tokenType", "refresh")
            .jwtID(UUID.randomUUID().toString())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(expiry))
            .build();
    return new IssuedToken(sign(claims), expiry, (int) Duration.between(now, expiry).getSeconds());
  }

  /**
   * Returns the refresh token's subject (username) if it is a validly signed, unexpired refresh
   * token.
   */
  public String verifyRefresh(String token) {
    try {
      SignedJWT jwt = SignedJWT.parse(token);
      if (!jwt.verify(new RSASSAVerifier(publicKey))) {
        throw new InvalidRefreshTokenException("Signature verification failed");
      }
      JWTClaimsSet claims = jwt.getJWTClaimsSet();
      if (!"refresh".equals(claims.getStringClaim("tokenType"))) {
        throw new InvalidRefreshTokenException("Not a refresh token");
      }
      if (claims.getExpirationTime() == null || claims.getExpirationTime().before(new Date())) {
        throw new InvalidRefreshTokenException("Refresh token expired");
      }
      return claims.getSubject();
    } catch (java.text.ParseException | JOSEException e) {
      throw new InvalidRefreshTokenException("Malformed refresh token", e);
    }
  }

  public boolean cookieSecure() {
    return properties.cookieSecure();
  }

  public int refreshTokenTtlSeconds() {
    return (int) Duration.ofDays(properties.refreshTokenTtlDays()).getSeconds();
  }

  private String sign(JWTClaimsSet claims) {
    try {
      SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
      jwt.sign(new RSASSASigner(privateKey));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException("Failed to sign JWT", e);
    }
  }

  public record IssuedToken(String value, Instant expiresAt, int expiresInSeconds) {}

  public static class InvalidRefreshTokenException extends RuntimeException {
    public InvalidRefreshTokenException(String message) {
      super(message);
    }

    public InvalidRefreshTokenException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
