package com.conveyor.order.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ADR-5: order-service is the only service that <em>issues</em> tokens ({@code POST
 * /api/v1/auth/login}), so it is the only service that ever needs the private half of the keypair
 * whose public half {@code conveyor-common}'s {@code JwtSecurityProperties} ships as a demo default
 * for every resource server to verify against. The default below is the matching private key from
 * that same {@code openssl genrsa} session (see {@code conveyor-common}'s {@code TestJwtSupport}
 * Javadoc) — safe to commit only because this is an explicitly non-production demo keypair; {@code
 * CONVEYOR_SECURITY_JWT_PRIVATE_KEY_PEM} overrides it for any real deployment, per ARCHITECTURE.md
 * §12's "mounted Secret" posture.
 */
@ConfigurationProperties(prefix = "conveyor.security.jwt")
public record JwtIssuerProperties(
    String privateKeyPem,
    Integer accessTokenTtlMinutes,
    Integer refreshTokenTtlDays,
    Boolean cookieSecure) {

  static final String DEV_PRIVATE_KEY_PKCS8_PEM =
      """
      MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCcd/AInPYQ4CJ1
      cEDGMb9UtiWdeUR3Q/w0GUsGbq8LIofxz6sv9OBjq8L9IMm89/XyuH4uUaNmHhQp
      WnX4gHO9LShGdWJ0CRlb70m9WiBxtUGrblBvDLnAfYuXTHo3vnS+SxpFKoFZW70C
      nmRZd1VvMCw8Loj5uXzBHMYzv5zVGfxsHKu2aI9gx3NyWoBkZm0dqa6MB+8hF3Ik
      4I6CdOaGfBN2+J431bE3tpYCiwBxetKvaWeS6mjdX0ATGf9m9iKSB6QG1GN2jwvl
      Vz9z+mEk24bjZQibCfBabysGizIuslo5aSN9D56azwg0m8o4WLRM+46rORPvRNxe
      xpVaCxvjAgMBAAECggEAL9wH0bqhEXxdTequBXGGApVMYCSNqqVi6VSrPCZy6EcB
      qhdJV3vhfts041Q6IND/q+R+xBA4mK2uoQ+IciBoRn8fiJ2zJab62MISnhaJQf6d
      PaCafb04vAYqwnakE5TwBJzYRjvAIOMMp1Znf24e9cmYXjglsazo2fDBN2buw8ea
      i+4HAv6oAIgGyQ3wVsF1MUGBwLf0dYqtA+FpCCaqtXWBb3jlxN3vRa6Wg3wmOmMy
      8xQrWSWFfPsGxzdpHN1zmAbOrM0ZOaueDJoSMUlSW5GQ05ALKi6zBoj5kZt0xEou
      Eu/zpueJquwEKE2XtEIcCNxlf2SE+r43cFyNa2HZUQKBgQDOv/oFHahgz3gYYagv
      yMjyGwA74TZXFVYsXF/n0aLUJWN2w9Q4vLGNk/BnLrKwcUAEiseYtUawChGjEP5l
      PBsRTNqKuc8soDeZfLkcw102xBQgaLvxq7XW8hBiOUycja7rveO6bXefF9nvLEJ1
      2sxtNdAdrnBDPvL6lPjiQpMixwKBgQDBvbNN7hAP7bE9EORBeoQrdz+xucoleWok
      7hgFwseqX6nAa2R9NjwR0RyWJBZOBwFln+5S/9qNCpFgmZ27D58dxD0hOUEbrVZN
      FxAdsSgQPOuCtp7JhDXRQ3AF/bv2NCT89oB/uwl4eBS0PiRbXZn2zUyDQIuQAI4h
      qNr73AsiBQKBgCjrcCWRECFRDrjsoygJ+lOIqowvb9zeeTbAda7hG/QXDk+URK2S
      EyYtUJhrcqxfTcdYXFbKEhqHc6QtmdwZgFX1Ow/X5Lw1XavANrcNp6ZOOpmLgR88
      1/mZ4Uo/gv09QZCg/bCJN/LB+r1Oqjy/OFSpIO6u9sMoc1jLIVNOz+ZDAoGBAI+c
      EUQL2iYkd8OfOML8kOozO6h+4kPC6xYy0uW6SwyUWp0CPfu+bup6CemVGF+AO93b
      neoyMwtnMPndBJk7bCPBadqtuQBODXGZTd3kiqD2t1AuFCel88qJZYlbWq+WWXCV
      PAzyVIPS5u3wPjzndhAGf9euyYTVlIWIx8H3it0NAoGAI2FV/BHYpLvpvfIl43E3
      4mCKYHPO9w2luXeunrdpSMWMl184rm4oMdIqhkYTxfy+MBPbqu9duryXvJOLg4E7
      oUA7w1aFEQle844irYxaF1kYujGIYHKpLuKXLIaR6efx5sjxMjfs/sPH6OhfEOLG
      xq/lMo1XqUpWNCYzX/SGKp0=
      """;

  public JwtIssuerProperties {
    privateKeyPem =
        normalize(
            privateKeyPem == null || privateKeyPem.isBlank()
                ? DEV_PRIVATE_KEY_PKCS8_PEM
                : privateKeyPem);
    accessTokenTtlMinutes = accessTokenTtlMinutes == null ? 15 : accessTokenTtlMinutes;
    refreshTokenTtlDays = refreshTokenTtlDays == null ? 30 : refreshTokenTtlDays;
    // Local dev serves the stack over plain http://localhost; a Secure cookie would silently
    // never be sent by the browser there. Real deployments override this back to true.
    cookieSecure = cookieSecure == null ? true : cookieSecure;
  }

  private static String normalize(String value) {
    return value
        .replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "")
        .replaceAll("\\s", "");
  }
}
