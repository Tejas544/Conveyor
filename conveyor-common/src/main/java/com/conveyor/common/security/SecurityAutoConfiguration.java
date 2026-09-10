package com.conveyor.common.security;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * ADR-5: every service is a Spring Security resource server validating the same self-issued RS256
 * token. Registered once here so no service hand-rolls its own — a repeat of this module's other
 * auto-configurations (outbox, chaos, error handling).
 *
 * <p><strong>HTTP-level access is left open by design.</strong> ADR-5 says "authenticated:
 * everything else," but PLAN.md rolls role-gating out incrementally, one endpoint at a time,
 * starting with inventory-service's {@code POST /adjust} (Phase 4) — retrofitting every existing
 * public endpoint across every service is not this phase's job and isn't done silently here. The
 * real enforcement is Spring Security <strong>method security</strong>: individual controller
 * methods carry {@code @PreAuthorize("hasRole('ADMIN')")}. An anonymous request still resolves to
 * an authenticated principal with {@code ROLE_ANONYMOUS}, so {@code hasRole(...)} correctly denies
 * it even with the filter chain itself wide open — no endpoint this project has not explicitly
 * gated changes behavior.
 */
@AutoConfiguration
@EnableConfigurationProperties(JwtSecurityProperties.class)
@ConditionalOnClass(SecurityFilterChain.class)
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityAutoConfiguration {

  @Bean
  public JwtDecoder jwtDecoder(JwtSecurityProperties properties) throws Exception {
    byte[] der = Base64.getDecoder().decode(properties.publicKeyPem());
    RSAPublicKey publicKey =
        (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    return NimbusJwtDecoder.withPublicKey(publicKey).build();
  }

  /** ADR-5: roles travel in a {@code roles} claim, e.g. {@code ["ADMIN"]}. */
  @Bean
  public JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtGrantedAuthoritiesConverter rolesConverter = new JwtGrantedAuthoritiesConverter();
    rolesConverter.setAuthoritiesClaimName("roles");
    rolesConverter.setAuthorityPrefix("ROLE_");

    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(rolesConverter);
    return converter;
  }

  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
    http.csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .oauth2ResourceServer(
            oauth2 ->
                oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
    return http.build();
  }
}
