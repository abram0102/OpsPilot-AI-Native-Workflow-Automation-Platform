package com.opspilot.organization;

import com.opspilot.auth.AuthenticatedPrincipal;
import com.opspilot.auth.OrganizationAccessService;
import com.opspilot.auth.User;
import com.opspilot.auth.UserRepository;
import com.opspilot.common.web.ConflictException;
import com.opspilot.common.web.NotFoundException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganizationServiceImpl implements OrganizationService {

  private final OrganizationRepository organizations;
  private final MembershipRepository memberships;
  private final UserRepository users;
  private final OrganizationAccessService accessService;

  public OrganizationServiceImpl(
      OrganizationRepository organizations,
      MembershipRepository memberships,
      UserRepository users,
      OrganizationAccessService accessService) {
    this.organizations = organizations;
    this.memberships = memberships;
    this.users = users;
    this.accessService = accessService;
  }

  @Override
  @Transactional
  public OrganizationResponse createOrganization(UUID creatorUserId, String name, String slug) {
    if (organizations.existsBySlug(slug)) {
      throw new ConflictException("That organization URL is already taken.");
    }
    Organization organization = organizations.save(new Organization(name, slug, creatorUserId));
    memberships.save(new Membership(organization.getId(), creatorUserId, MembershipRole.OWNER));
    return new OrganizationResponse(
        organization.getId(),
        organization.getName(),
        organization.getSlug(),
        organization.getCreatedAt(),
        MembershipRole.OWNER);
  }

  @Override
  public OrganizationResponse getOrganization(
      AuthenticatedPrincipal principal, UUID organizationId) {
    MembershipRole role = accessService.requireMembership(principal, organizationId);
    Organization organization =
        organizations
            .findById(organizationId)
            .orElseThrow(() -> new NotFoundException("Organization not found."));
    return new OrganizationResponse(
        organization.getId(),
        organization.getName(),
        organization.getSlug(),
        organization.getCreatedAt(),
        role);
  }

  @Override
  public List<MemberResponse> listMembers(AuthenticatedPrincipal principal, UUID organizationId) {
    accessService.requireMembership(principal, organizationId);
    List<Membership> rows = memberships.findAllByOrganizationId(organizationId);
    Map<UUID, User> usersById =
        users.findAllById(rows.stream().map(Membership::getUserId).toList()).stream()
            .collect(Collectors.toMap(User::getId, Function.identity()));
    return rows.stream()
        .map(membership -> {
          User user = usersById.get(membership.getUserId());
          return new MemberResponse(
              membership.getId(),
              user.getId(),
              user.getEmail(),
              user.getDisplayName(),
              membership.getRole(),
              membership.getCreatedAt());
        })
        .toList();
  }
}
