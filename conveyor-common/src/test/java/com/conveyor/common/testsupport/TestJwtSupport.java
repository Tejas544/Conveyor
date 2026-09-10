package com.conveyor.common.testsupport;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * Signs RS256 test tokens with the private half of the demo keypair that {@link
 * com.conveyor.common.security.JwtSecurityProperties}' default public key trusts — so integration
 * tests in any service can mint a valid {@code Authorization: Bearer} token for
 * {@code @PreAuthorize}-gated endpoints without a live auth issuer. ADR-5's {@code POST
 * /auth/login} lands later (see CONTEXT.md's Key Decisions Log); until then, this is the only
 * source of tokens, in tests and only in tests — the private key below never appears in main
 * source.
 */
public final class TestJwtSupport {

  // The base64 body of a PEM PKCS8 "PRIVATE KEY" block, pasted verbatim (openssl's own 64-column
  // wrapping) rather than manually re-flowed, so there is no hand-computed line-break boundary
  // that could silently corrupt the key.
  private static final String TEST_PRIVATE_KEY_PKCS8_PEM =
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

  private TestJwtSupport() {}

  /** Mints a short-lived RS256 token carrying {@code roles} in the claim ADR-5 specifies. */
  public static String token(String subject, String... roles) {
    try {
      String base64 = TEST_PRIVATE_KEY_PKCS8_PEM.replaceAll("\\s", "");
      RSAPrivateKey privateKey =
          (RSAPrivateKey)
              KeyFactory.getInstance("RSA")
                  .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));

      JWTClaimsSet claims =
          new JWTClaimsSet.Builder()
              .subject(subject)
              .claim("roles", List.of(roles))
              .issueTime(new Date())
              .expirationTime(new Date(System.currentTimeMillis() + 15 * 60 * 1000))
              .build();

      SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
      jwt.sign(new RSASSASigner(privateKey));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException("Failed to mint test JWT", e);
    }
  }
}
