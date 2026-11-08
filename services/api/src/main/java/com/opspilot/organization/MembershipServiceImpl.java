package com.opspilot.organization;

import com.opspilot.auth.AuthenticatedPrincipal;
import com.opspilot.auth.OrganizationAccessService;
import com.opspilot.auth.User;
import com.opspilot.auth.UserRepository;
import com.opspilot.common.web.ConflictException;
import com.opspilot.common.web.NotFoundException;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MembershipServiceImpl implements MembershipService {

  private final MembershipRepository memberships;
  private final UserRepository users;
  private final OrganizationAccessService accessService;

  public MembershipServiceImpl(
      MembershipRepository memberships,
      UserRepository users,
      OrganizationAccessService accessService) {
    this.memberships = memberships;
    this.users = users;
    this.accessService = accessService;
  }

  @Override
  @Transactional
  public MemberResponse updateRole(
      AuthenticatedPrincipal principal,
      UUID organizationId,
      UUID membershipId,
      MembershipRole newRole) {
    MembershipRole callerRole = accessService.requireMembership(principal, organizationId);
    if (callerRole.compareTo(MembershipRole.ADMIN) < 0) {
      throw new AccessDeniedException("You cannot perform this action.");
    }

    Membership target =
        memberships
            .findById(membershipId)
            .filter(membership -> membership.getOrganizationId().equals(organizationId))
            .orElseThrow(() -> new NotFoundException("Member not found."));

    if (newRole == MembershipRole.OWNER && callerRole != MembershipRole.OWNER) {
      throw new AccessDeniedException("Only an owner can grant owner rights.");
    }

    if (target.getRole() == MembershipRole.OWNER && newRole != MembershipRole.OWNER) {
      long ownerCount =
          memberships.countByOrganizationIdAndRole(organizationId, MembershipRole.OWNER);
      if (ownerCount <= 1) {
        throw new ConflictException("The last owner cannot be demoted.");
      }
    }

    target.setRole(newRole);
    Membership saved = memberships.save(target);
    User user =
        users
            .findById(saved.getUserId())
            .orElseThrow(() -> new IllegalStateException("Membership references a missing user."));
    return new MemberResponse(
        saved.getId(),
        user.getId(),
        user.getEmail(),
        user.getDisplayName(),
        saved.getRole(),
        saved.getCreatedAt());
  }
}
