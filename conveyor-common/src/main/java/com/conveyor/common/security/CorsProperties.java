package com.conveyor.common.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Phase 14: the dashboard is deployable as a statically-hosted SPA (GitHub Pages) independent of
 * whichever backend cluster it happens to be pointed at (ARCHITECTURE.md §15.2) — that pairing is
 * cross-origin by construction (different scheme/host/port), unlike every prior phase's dev/compose
 * setup where the Vite dev-server proxy or a shared origin made CORS a non-issue. Empty by default
 * (no origin allowed) so every existing deployment shape (compose, kind, local `npm run dev`) is
 * unaffected — this is additive, opt-in configuration, not a default-open CORS policy.
 */
@ConfigurationProperties(prefix = "conveyor.security.cors")
public record CorsProperties(List<String> allowedOrigins) {

  public CorsProperties {
    allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
  }
}
