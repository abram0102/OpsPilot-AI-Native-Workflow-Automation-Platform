package com.opspilot.organization;

import com.opspilot.auth.AuthenticatedPrincipal;
import com.opspilot.auth.PrincipalAuthenticationToken;
import com.opspilot.auth.User;
import com.opspilot.auth.UserProvisioningService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Invitation issuance and acceptance (docs/API_CONTRACT.md; docs/TASKS.md T09). No email is sent in
 * V1 — {@link InvitationResponse#token()} is the "protected preview" docs/TASKS.md asks for, safe
 * to return here because the caller has already passed the owner/admin check for this organization.
 */
@RestController
class InvitationController {

  private final InvitationService invitationService;
  private final UserProvisioningService userProvisioningService;

  InvitationController(
      InvitationService invitationService, UserProvisioningService userProvisioningService) {
    this.invitationService = invitationService;
    this.userProvisioningService = userProvisioningService;
  }

  @PostMapping("/api/v1/organizations/{orgId}/invitations")
  ResponseEntity<InvitationResponse> create(
      @PathVariable UUID orgId,
      @Valid @RequestBody CreateInvitationRequest request,
      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    InvitationResponse response =
        invitationService.createInvitation(principal, orgId, request.email(), request.role());
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @PostMapping("/api/v1/invitations/accept")
  AcceptInvitationResponse accept(
      @Valid @RequestBody AcceptInvitationRequest request,
      PrincipalAuthenticationToken authentication) {
    User user = userProvisioningService.ensureProvisioned(authentication.getJwt());
    return invitationService.acceptInvitation(user.getId(), user.getEmail(), request.token());
  }
}
