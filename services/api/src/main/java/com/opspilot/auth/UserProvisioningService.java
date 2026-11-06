package com.opspilot.auth;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Creates the internal {@link User} row for a verified identity the first time it's seen (T08:
 * "Provision internal user by stable OIDC sub on first trusted login"). {@link
 * AuthenticatedPrincipal}'s own javadoc names this as this interface's job.
 */
public interface UserProvisioningService {

  /**
   * Namespaced custom access-token claim an Auth0 Post-Login Action copies the user's email into
   * (see docs/MANUAL_SETUP.md Gate H1). Access tokens for a custom API audience do not carry
   * profile claims by default — only a value placed here by Auth0 itself, inside the signed token,
   * counts as "verified email provenance" (docs/DATA_MODEL.md); a value merely supplied by a caller
   * never does (AGENTS.md rule 11).
   */
  String EMAIL_CLAIM = "https://opspilot.local/email";

  /** Same reasoning as {@link #EMAIL_CLAIM}, for the display name. */
  String NAME_CLAIM = "https://opspilot.local/name";

  /**
   * Returns the {@link User} for this JWT's {@code sub}, creating it on first sight. Idempotent:
   * concurrent first logins for the same subject never produce two rows or fail the caller.
   */
  User ensureProvisioned(Jwt jwt);
}
