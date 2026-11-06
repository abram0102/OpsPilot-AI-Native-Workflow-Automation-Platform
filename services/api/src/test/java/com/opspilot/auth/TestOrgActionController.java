package com.opspilot.auth;

import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints exercising {@link OrganizationAccessService} over real HTTP — one gated at
 * {@link OrganizationAction#MANAGE_TICKETS} (MEMBER+), one at {@link
 * OrganizationAction#MANAGE_MEMBERS} (ADMIN+) — to prove docs/TASKS.md T07's "owner/member/viewer
 * role tests prove server checks" without inventing a real business endpoint ahead of T09/T10.
 * Never shipped in the production jar.
 */
@RestController
class TestOrgActionController {

  private final OrganizationAccessService accessService;

  TestOrgActionController(OrganizationAccessService accessService) {
    this.accessService = accessService;
  }

  @GetMapping("/__test/organizations/{orgId}/member-action")
  Map<String, String> memberAction(
      @AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable UUID orgId) {
    accessService.requireAction(principal, orgId, OrganizationAction.MANAGE_TICKETS);
    return Map.of("status", "ok");
  }

  @GetMapping("/__test/organizations/{orgId}/admin-action")
  Map<String, String> adminAction(
      @AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable UUID orgId) {
    accessService.requireAction(principal, orgId, OrganizationAction.MANAGE_MEMBERS);
    return Map.of("status", "ok");
  }
}
