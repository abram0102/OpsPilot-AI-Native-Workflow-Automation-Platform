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

  public ProductionSafetyGuard(
      Environment environment,
      @Value("${app.security.allow-test-issuer:false}") boolean allowTestIssuer) {
    if (allowTestIssuer && environment.acceptsProfiles(Profiles.of("production"))) {
      throw new IllegalStateException(
          "app.security.allow-test-issuer=true is not allowed under the 'production' profile."
              + " Refusing to start.");
    }
  }
}
