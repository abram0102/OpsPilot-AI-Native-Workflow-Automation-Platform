package com.opspilot.auth;

import com.opspilot.organization.MembershipRole;
import java.util.UUID;

/**
 * The one place tenant membership and role are checked. Every organization-scoped
 * controller/service calls this instead of trusting a path variable, a request body field, or an
 * assumed role (AGENTS.md rule 11; docs/API_CONTRACT.md: "{orgId} is an identifier, NOT
 * authorization").
 */
public interface OrganizationAccessService {

  /**
   * @return the caller's role in {@code organizationId}.
   * @throws org.springframework.security.access.AccessDeniedException if the principal is
   *     unresolved or has no membership there.
   */
  MembershipRole requireMembership(AuthenticatedPrincipal principal, UUID organizationId);

  /**
   * @throws org.springframework.security.access.AccessDeniedException if the caller's role in
   *     {@code organizationId} is below {@code action}'s minimum (see {@link OrganizationAction}).
   */
  void requireAction(
      AuthenticatedPrincipal principal, UUID organizationId, OrganizationAction action);
}
