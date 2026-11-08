package com.opspilot.organization;

import com.opspilot.auth.AuthenticatedPrincipal;
import java.util.UUID;

/**
 * Invitation issuance and acceptance (docs/API_CONTRACT.md: {@code POST
 * /organizations/{orgId}/invitations}, {@code POST /invitations/accept}).
 */
public interface InvitationService {

  /**
   * @throws org.springframework.security.access.AccessDeniedException if the caller is below {@code
   *     ADMIN}, or is inviting {@code OWNER} while not {@code OWNER} themselves.
   */
  InvitationResponse createInvitation(
      AuthenticatedPrincipal principal, UUID organizationId, String email, MembershipRole role);

  /**
   * Verifies the hashed token, binds it to {@code verifiedEmail} (the caller's own server-resolved
   * email, never a client-supplied one), and checks expiry and one-time use, all before creating
   * the membership (docs/API_CONTRACT.md).
   *
   * @throws com.opspilot.common.web.NotFoundException if no invitation matches this token and email
   *     — the same response for "token does not exist" and "token exists for a different email", so
   *     neither confirms the other to an unintended caller.
   * @throws com.opspilot.common.web.ConflictException if the invitation is expired, already used,
   *     or the caller already belongs to the organization.
   */
  AcceptInvitationResponse acceptInvitation(UUID userId, String verifiedEmail, String rawToken);
}
