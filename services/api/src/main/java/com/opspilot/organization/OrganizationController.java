package com.opspilot.organization;

import com.opspilot.auth.AuthenticatedPrincipal;
import com.opspilot.auth.PrincipalAuthenticationToken;
import com.opspilot.auth.User;
import com.opspilot.auth.UserProvisioningService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Organization creation, lookup, membership listing and role management (docs/API_CONTRACT.md
 * "User/org"; docs/TASKS.md T09). {@code orgId} is an identifier, never authorization — every
 * method resolves the caller's actual membership server-side (AGENTS.md rule 11), via {@link
 * OrganizationService}/{@link MembershipService}.
 */
@RestController
@RequestMapping("/api/v1/organizations")
class OrganizationController {

  private final OrganizationService organizationService;
  private final MembershipService membershipService;
  private final UserProvisioningService userProvisioningService;

  OrganizationController(
      OrganizationService organizationService,
      MembershipService membershipService,
      UserProvisioningService userProvisioningService) {
    this.organizationService = organizationService;
    this.membershipService = membershipService;
    this.userProvisioningService = userProvisioningService;
  }

  @PostMapping
  ResponseEntity<OrganizationResponse> create(
      @Valid @RequestBody CreateOrganizationRequest request,
      PrincipalAuthenticationToken authentication) {
    User creator = userProvisioningService.ensureProvisioned(authentication.getJwt());
    OrganizationResponse response =
        organizationService.createOrganization(creator.getId(), request.name(), request.slug());
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @GetMapping("/{orgId}")
  OrganizationResponse get(
      @PathVariable UUID orgId, @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return organizationService.getOrganization(principal, orgId);
  }

  @GetMapping("/{orgId}/members")
  List<MemberResponse> members(
      @PathVariable UUID orgId, @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return organizationService.listMembers(principal, orgId);
  }

  @PatchMapping("/{orgId}/members/{memberId}")
  MemberResponse updateMemberRole(
      @PathVariable UUID orgId,
      @PathVariable UUID memberId,
      @Valid @RequestBody UpdateMemberRoleRequest request,
      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return membershipService.updateRole(principal, orgId, memberId, request.role());
  }
}
