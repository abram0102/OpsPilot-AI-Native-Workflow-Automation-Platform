package com.opspilot.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * T28 acceptance: "no publicly reachable test auth shortcut".
 *
 * <p>{@link ProductionSafetyGuard} is the only thing standing between a production deployment and
 * either of the two shortcuts this repository deliberately ships for local development: the
 * self-signed test OIDC issuer, and the internal-service signing secret that is committed to {@code
 * .env.example}. Both are published — anyone reading the source can mint a token with them — so the
 * guard refuses to let the application finish starting rather than serving one request.
 *
 * <p>It had no tests until this ticket, which is the wrong state for a component whose whole job is
 * to fail. These run it as a real bean in a real context so a failure here is the same event an
 * operator would see: the context does not come up.
 *
 * <p>No database, no Docker — the guard is a constructor-time check, so this is a fast unit-level
 * slice and runs everywhere.
 */
@Tag("security")
class ProductionSafetyGuardTest {

  /** Committed to {@code .env.example}, therefore public. Must never be a production secret. */
  private static final String PUBLISHED_SECRET =
      "local-development-only-internal-service-secret-not-for-production";

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(ProductionSafetyGuard.class);

  @Test
  void theTestOnlyIssuerUnderTheProductionProfileRefusesToStart() {
    contextRunner
        .withPropertyValues(
            "spring.profiles.active=production",
            "app.security.allow-test-issuer=true",
            "app.security.internal-service.signing-secret=a-real-deployment-secret")
        .run(context ->
            assertThat(context)
                .hasFailed()
                .getFailure()
                .rootCause()
                .hasMessageContaining("allow-test-issuer=true is not allowed"));
  }

  @Test
  void aPublishedInternalSigningSecretUnderTheProductionProfileRefusesToStart() {
    contextRunner
        .withPropertyValues(
            "spring.profiles.active=production",
            "app.security.allow-test-issuer=false",
            "app.security.internal-service.signing-secret=" + PUBLISHED_SECRET)
        .run(context ->
            assertThat(context)
                .hasFailed()
                .getFailure()
                .rootCause()
                .hasMessageContaining("committed to"));
  }

  @Test
  void theOtherPublishedSecretIsRefusedToo() {
    // Both development defaults that have ever been committed are listed in the guard. A new one
    // added to .env.example without being listed here is exactly the hole this asserts against —
    // so the test names them rather than reusing the guard's own constant.
    contextRunner
        .withPropertyValues(
            "spring.profiles.active=production",
            "app.security.internal-service.signing-secret=replace-with-a-local-only-test-secret")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void aProperlyConfiguredProductionDeploymentStartsNormally() {
    // The control. A guard that refused every production configuration would pass the three tests
    // above and ship nothing.
    contextRunner
        .withPropertyValues(
            "spring.profiles.active=production",
            "app.security.allow-test-issuer=false",
            "app.security.internal-service.signing-secret=a-secret-from-the-secrets-manager")
        .run(context ->
            assertThat(context).hasNotFailed().hasSingleBean(ProductionSafetyGuard.class));
  }

  @Test
  void theSameShortcutsAreAllowedWhenTheProductionProfileIsNotActive() {
    // Local development is the whole reason these exist. The guard keys on the profile, not on the
    // value, so this is the other half of the claim: it blocks production, not developers.
    contextRunner
        .withPropertyValues(
            "spring.profiles.active=local",
            "app.security.allow-test-issuer=true",
            "app.security.internal-service.signing-secret=" + PUBLISHED_SECRET)
        .run(context -> assertThat(context).hasNotFailed());
  }
}
