package com.opspilot.organization;

import com.opspilot.auth.AuthenticatedPrincipal;
import java.util.List;
import java.util.UUID;

/**
 * Organization creation and read access (docs/API_CONTRACT.md "User/org" table). Every read
 * requires an existing membership, resolved server-side via {@link
 * com.opspilot.auth.OrganizationAccessService} — never trusted from the path (AGENTS.md rule 11).
 */
public interface OrganizationService {

  /**
   * Creates the organization and its creator's {@code OWNER} membership atomically
   * (docs/API_CONTRACT.md: "Creates org + owner membership atomically").
   *
   * @throws com.opspilot.common.web.ConflictException if {@code slug} is already taken.
   */
  OrganizationResponse createOrganization(UUID creatorUserId, String name, String slug);

  /**
   * @throws org.springframework.security.access.AccessDeniedException if the caller is not a member
   *     of {@code organizationId}.
   */
  OrganizationResponse getOrganization(AuthenticatedPrincipal principal, UUID organizationId);

  /**
   * @throws org.springframework.security.access.AccessDeniedException if the caller is not a member
   *     of {@code organizationId}.
   */
  List<MemberResponse> listMembers(AuthenticatedPrincipal principal, UUID organizationId);
}
