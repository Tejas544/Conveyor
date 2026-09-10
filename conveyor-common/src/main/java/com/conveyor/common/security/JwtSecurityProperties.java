package com.conveyor.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ADR-5, ARCHITECTURE.md §12: the RS256 verification key every service's resource server trusts.
 * {@link #publicKeyPem()} is base64-encoded X.509 {@code SubjectPublicKeyInfo} DER (whitespace
 * tolerated and stripped — see {@link #normalize(String)} — so it can be supplied either as one
 * unbroken line in an env var or pasted PEM-wrapped).
 *
 * <p>The default below is a committed demo/dev keypair — a public key is not a secret, so this is
 * safe to commit, unlike the matching private key (which never appears in application code; see
 * conveyor-common's {@code TestJwtSupport} in the test-jar — generated together with this key by
 * the same {@code openssl genrsa}/{@code openssl rsa -pubout} session). §12's "mounted Secret"
 * production posture is honoured by overriding {@code CONVEYOR_SECURITY_JWT_PUBLIC_KEY_PEM}
 * (Spring's relaxed binding for {@code conveyor.security.jwt.public-key-pem}), never by editing
 * this default.
 */
@ConfigurationProperties(prefix = "conveyor.security.jwt")
public record JwtSecurityProperties(String publicKeyPem) {

  // The base64 body of a PEM "PUBLIC KEY" block, pasted verbatim (openssl's own 64-column
  // wrapping) rather than manually re-flowed, so there is no hand-computed line-break boundary
  // that could silently corrupt the key.
  static final String DEV_PUBLIC_KEY_PEM =
      """
      MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAnHfwCJz2EOAidXBAxjG/
      VLYlnXlEd0P8NBlLBm6vCyKH8c+rL/TgY6vC/SDJvPf18rh+LlGjZh4UKVp1+IBz
      vS0oRnVidAkZW+9JvVogcbVBq25Qbwy5wH2Ll0x6N750vksaRSqBWVu9Ap5kWXdV
      bzAsPC6I+bl8wRzGM7+c1Rn8bByrtmiPYMdzclqAZGZtHamujAfvIRdyJOCOgnTm
      hnwTdvieN9WxN7aWAosAcXrSr2lnkupo3V9AExn/ZvYikgekBtRjdo8L5Vc/c/ph
      JNuG42UImwnwWm8rBosyLrJaOWkjfQ+ems8INJvKOFi0TPuOqzkT70TcXsaVWgsb
      4wIDAQAB
      """;

  public JwtSecurityProperties {
    publicKeyPem =
        normalize(
            publicKeyPem == null || publicKeyPem.isBlank() ? DEV_PUBLIC_KEY_PEM : publicKeyPem);
  }

  /** Strips all whitespace (and any PEM headers, if present) so callers can paste either form. */
  private static String normalize(String value) {
    return value
        .replace("-----BEGIN PUBLIC KEY-----", "")
        .replace("-----END PUBLIC KEY-----", "")
        .replaceAll("\\s", "");
  }
}
