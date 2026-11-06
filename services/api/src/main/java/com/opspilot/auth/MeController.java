package com.opspilot.auth;

import com.opspilot.organization.Membership;
import com.opspilot.organization.MembershipRepository;
import com.opspilot.organization.Organization;
import com.opspilot.organization.OrganizationRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/me} (docs/API_CONTRACT.md): the internal profile and accessible organizations
 * for whoever the validated JWT belongs to. No request body and no client-supplied identifiers —
 * everything returned is looked up server-side from the token's own {@code sub} (AGENTS.md rule
 * 11). Reachable by any authenticated caller (the global filter chain already requires a valid JWT
 * for every path but the health probes); provisioning a brand-new subject happens here, the first
 * place a freshly-logged-in user's token is actually used (T08).
 */
@RestController
@RequestMapping("/api/v1/me")
class MeController {

  private final UserProvisioningService userProvisioningService;
  private final MembershipRepository membershipRepository;
  private final OrganizationRepository organizationRepository;

  MeController(
      UserProvisioningService userProvisioningService,
      MembershipRepository membershipRepository,
      OrganizationRepository organizationRepository) {
    this.userProvisioningService = userProvisioningService;
    this.membershipRepository = membershipRepository;
    this.organizationRepository = organizationRepository;
  }

  @GetMapping
  MeResponse me(PrincipalAuthenticationToken authentication) {
    User user = userProvisioningService.ensureProvisioned(authentication.getJwt());
    List<Membership> memberships = membershipRepository.findAllByUserId(user.getId());
    Map<UUID, Organization> organizationsById =
        organizationRepository
            .findAllById(memberships.stream().map(Membership::getOrganizationId).toList())
            .stream()
            .collect(Collectors.toMap(Organization::getId, Function.identity()));

    List<MeResponse.OrganizationMembershipView> organizations =
        memberships.stream()
            .map(membership ->
                new MeResponse.OrganizationMembershipView(
                    membership.getOrganizationId(),
                    organizationsById.get(membership.getOrganizationId()).getName(),
                    membership.getRole()))
            .toList();

    return new MeResponse(user.getId(), user.getEmail(), user.getDisplayName(), organizations);
  }
}
