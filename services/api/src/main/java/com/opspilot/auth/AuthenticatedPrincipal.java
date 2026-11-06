package com.opspilot.auth;

import java.util.UUID;

/**
 * The resolved caller for the current request — always derived server-side from a validated JWT's
 * {@code sub} claim looked up against {@link User}, never trusted from any client-supplied value
 * (AGENTS.md rule 11).
 *
 * <p>{@code userId} is {@code null} when the JWT's subject is validly signed but does not (yet)
 * match a known {@link User} row. Auto-provisioning a user on first login is T08's job, not this
 * one; until then, an unresolved principal simply has no memberships anywhere, so every {@link
 * OrganizationAccessService} check on it fails closed.
 */
public record AuthenticatedPrincipal(UUID userId, String authSubject) {

  public static AuthenticatedPrincipal resolved(UUID userId, String authSubject) {
    return new AuthenticatedPrincipal(userId, authSubject);
  }

  public static AuthenticatedPrincipal unresolved(String authSubject) {
    return new AuthenticatedPrincipal(null, authSubject);
  }

  public boolean isResolved() {
    return userId != null;
  }
}
