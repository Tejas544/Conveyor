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

  private static final String TEST_PRIVATE_KEY_PKCS8_BASE64 =
      "MIIEvwIBADANBgkqhkiG9w0BAQEFAASCBKkwggSlAgEAAoIBAQDJBR1geHd3Fl7QII2QXPWnfIj3"
          + "CrSczFzIMOqkDceA8EzcoPvqRLsR5P7G1mnrkx8FL9QFpKUTONhdtsXUrG6sQUQ3nYcVyg8Craym"
          + "dAEquvdmigQisl+QEypiboLgLiLmm2tqT/uC67rnVlKfEhTcVwWWxhCyZ6qohH7MxC89ZHzxW4Ym"
          + "BdQ7WU7qY0thDyNthe5NNL6/Tu9WHT0Ox8a/glyAWS0507b8EY77n0sjbs+vv5zysQsaEWeIoI6k"
          + "K3rC4q9JKRALEs7LDiZ/2hV4NSHpN5OrxcxdewhhHRRlFBfsqnC+p4pQ6JT5tZgMZRqR8cQOx69+"
          + "PBEmg0MB967AgMBAAECggEABwJmuDEUT6BhIvrgwDzaJgJwc+jLlVIYX5AVqwk00uapmJi7BJdH"
          + "XuiUvpIPRrxAfN5QJa8ViZ76mZTOUZUQzEwkT7xuYcd62YNGGQYEyc8p8L9J/eyH1R88I/T5jGuP"
          + "sYTG7v58wx5lB9aj01rdEVWCOk4xAXKcMT7XWdA5q7B2lA8LYdxi0m0uHQWu9y6Dm7M+K11817Y1"
          + "1mOjLo5Gb3fv0r1R4mKwCwnIRv7LZkvMZbntknMIfPdpu8A/y1nCJYYRmusRM2a074Fao+KAwJit"
          + "i4aBc1tqu/jZg6f9/cUKjTzzC2jheUB1aevhwE/te2m78gHzE8E6qUeTK2il7QKBgQDvWKVzdKDA"
          + "PuazyMSrrpgsWTEgT3w5X2Ey/AUi4EGnn5oV0UdtoqbtutekgvEWQehQpIWAm4ahWKDPMzU6dxrq"
          + "xhNf+xCpeqLaZ9iBkI0am5Um9SULoZVhKaEgfuYwo9MrhsssZjgMbpuW5fT4RtAuhOXe9OGwrGsg"
          + "6pWuA0miVwKBgQDXAcgYLXRGZEq6QpQIU8+bzb7frPlmIFITlh5rzEqnSgakgLYeUBjRmcAKf5nr"
          + "hwa0O1XbOx++OrNjRm7Oveq5ZAPb8Tyv6Sm77BNz4B4P1L/MsTp1XfxoCr4Sbx5EkdEUVRdfXRKN"
          + "Q+sEShJkL84qNG54IqBvnTCv3yv4NqdQPQKBgQDpLJxrQXkGMYGCLxrjAwI/WllQ1/72yeQgzoOW"
          + "eZGc4xEzJiKHPcmQmtFQ9Tw4adcREWb6ZwofEAACPCokHjr79CKWBDs0UURssHStrQy6mk4RmQwR"
          + "K8ci1HKj/Nz3D/M/WV+AjskV23/632brpdlVKKXlsv5Yp3DqrX9K+ur2mwKBgQCTUdQxxMtcBBIz"
          + "57SYtByXi/VSO6ozcMfsRbsYb8VjNNSyWMLwqD8pNukgCGiFumI8kj901OEeLgiGaFc6b2TqnH4M"
          + "cRH9Eo0XB14Y0qKmhEbbUUBV9Q0imOG9rceWgjc5cEhwfkxc4QGiUcKiRSNFReG/jTJS5+jZSNhO"
          + "3dvsnQKBgQDEnAJMqBWnT8nphRanLSiPesK0wCwoFSITCfdPSEdRgZNhTtL8u6eqsK8/37ROYPc3"
          + "Y8VkzMMtZFXL9EcjiWvD9soCsaYLMUxMxxWafYeh6JK0vdj5VeejOf56pJpuDTBglKe1p+V/tAhe"
          + "OUhyrNj8VTFjwiABgswMydznqIiUjw==";

  private TestJwtSupport() {}

  /** Mints a short-lived RS256 token carrying {@code roles} in the claim ADR-5 specifies. */
  public static String token(String subject, String... roles) {
    try {
      RSAPrivateKey privateKey =
          (RSAPrivateKey)
              KeyFactory.getInstance("RSA")
                  .generatePrivate(
                      new PKCS8EncodedKeySpec(
                          Base64.getDecoder().decode(TEST_PRIVATE_KEY_PKCS8_BASE64)));

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
