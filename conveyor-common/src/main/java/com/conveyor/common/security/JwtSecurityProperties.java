package com.conveyor.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ADR-5, ARCHITECTURE.md §12: the RS256 verification key every service's resource server trusts.
 * Base64-encoded X.509 {@code SubjectPublicKeyInfo} DER (no PEM headers, no newlines) so it fits
 * one environment variable cleanly.
 *
 * <p>The default below is a committed demo/dev keypair — a public key is not a secret, so this is
 * safe to commit, unlike the matching private key (which never appears in application code; see
 * conveyor-common's {@code TestJwtSupport} in the test-jar). §12's "mounted Secret" production
 * posture is honoured by overriding {@code CONVEYOR_SECURITY_JWT_PUBLIC_KEY_PEM} (Spring's relaxed
 * binding for {@code conveyor.security.jwt.public-key-pem}), never by editing this default.
 */
@ConfigurationProperties(prefix = "conveyor.security.jwt")
public record JwtSecurityProperties(String publicKeyPem) {

  static final String DEV_PUBLIC_KEY_PEM =
      "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAyQUdYHh3dxZe0CCNkFz1p3yI9wq0nMxc"
          + "yDDqpA3HgPBM3KD76kS7EeT+xtZp65MfBS/UBaSlEzjYXbbF1KxurEFEN52HFcoPAq2so5nQBKrr"
          + "3ZooEIrJfkBMqYm6C4C4i5ptrak/7guu651ZSnxIU3FcFlsYQsmeqqIR+zMQvPWR88VuGJgXUO1l"
          + "O6mNLYQ8jbYXuTTS+v07vVh09DsfGv4JcgFktOdO2/BGO+59LI27Pr7+c8rELGhFniKCOpCt6wuK"
          + "vSSkQCxLOyw4mf9oVeDUh6TeTq8XMXXsIYR0UZRQX7KpwvqeKUOiU+bWYDGUakfHEDsevfjwRJoN"
          + "DAfeuwIDAQAB";

  public JwtSecurityProperties {
    if (publicKeyPem == null || publicKeyPem.isBlank()) {
      publicKeyPem = DEV_PUBLIC_KEY_PEM;
    }
  }
}
