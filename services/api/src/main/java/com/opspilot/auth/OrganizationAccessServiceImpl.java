package com.opspilot.auth;

import com.opspilot.organization.Membership;
import com.opspilot.organization.MembershipRepository;
import com.opspilot.organization.MembershipRole;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public class OrganizationAccessServiceImpl implements OrganizationAccessService {

  private final MembershipRepository memberships;

  public OrganizationAccessServiceImpl(MembershipRepository memberships) {
    this.memberships = memberships;
  }

  @Override
  public MembershipRole requireMembership(AuthenticatedPrincipal principal, UUID organizationId) {
    if (!principal.isResolved()) {
      throw new AccessDeniedException("Not a member of this organization.");
    }
    Membership membership =
        memberships
            .findByOrganizationIdAndUserId(organizationId, principal.userId())
            .orElseThrow(() -> new AccessDeniedException("Not a member of this organization."));
    return membership.getRole();
  }

  @Override
  public void requireAction(
      AuthenticatedPrincipal principal, UUID organizationId, OrganizationAction action) {
    MembershipRole role = requireMembership(principal, organizationId);
    if (role.compareTo(action.minimumRole()) < 0) {
      throw new AccessDeniedException("You cannot perform this action.");
    }
  }
}
