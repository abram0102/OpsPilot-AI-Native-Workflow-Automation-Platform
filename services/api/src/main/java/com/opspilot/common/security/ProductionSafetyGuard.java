package com.opspilot.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Fails application startup outright if the test-only OIDC issuer is enabled under the {@code
 * production} profile (docs/TASKS.md T07 acceptance: "production profile refuses test-only issuer";
 * docs/MANUAL_SETUP.md Gate H1: the test issuer must never be "a dev bypass exposed on production
 * routes"). A constructor-time check, so a misconfigured production deployment never finishes
 * starting rather than quietly accepting test-signed tokens.
 */
@Component
public class ProductionSafetyGuard {

  /**
   * Internal-service secrets that are committed to this repository.
   *
   * <p>Both are published, so either one in production would mean anyone reading the source could
   * mint a worker token for the internal action API. Listed explicitly rather than pattern-matched
   * so adding another development default is a deliberate edit here too.
   */
  private static final java.util.Set<String> PUBLISHED_INTERNAL_SECRETS =
      java.util.Set.of(
          "replace-with-a-local-only-test-secret",
          "local-development-only-internal-service-secret-not-for-production");

  public ProductionSafetyGuard(
      Environment environment,
      @Value("${app.security.allow-test-issuer:false}") boolean allowTestIssuer,
      @Value("${app.security.internal-service.signing-secret:}") String internalSigningSecret) {
    boolean production = environment.acceptsProfiles(Profiles.of("production"));

    if (allowTestIssuer && production) {
      throw new IllegalStateException(
          "app.security.allow-test-issuer=true is not allowed under the 'production' profile."
              + " Refusing to start.");
    }

    // T21: the example secret is committed to this repository, so a production instance running
    // on it would be accepting worker tokens anyone could mint. Same reasoning as the test
    // issuer — fail at startup rather than serve one request with it.
    if (production && PUBLISHED_INTERNAL_SECRETS.contains(internalSigningSecret)) {
      throw new IllegalStateException(
          "app.security.internal-service.signing-secret is a development default committed to"
              + " this repository, so it is public. Refusing to start under the 'production'"
              + " profile.");
    }
  }
}
