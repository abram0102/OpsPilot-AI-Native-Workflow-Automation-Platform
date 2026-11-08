package com.opspilot.organization;

import com.opspilot.auth.AuthenticatedPrincipal;
import com.opspilot.auth.OrganizationAccessService;
import com.opspilot.common.web.ConflictException;
import com.opspilot.common.web.NotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvitationServiceImpl implements InvitationService {

  /**
   * Single response text for every way {@link #acceptInvitation} can fail to find a usable
   * invitation for this caller — an unknown token and a token that belongs to someone else's email
   * must be indistinguishable to whoever is asking (AGENTS.md: conceal rather than confirm).
   */
  private static final String INVALID_INVITATION_MESSAGE = "This invitation link is invalid.";

  private final InvitationRepository invitations;
  private final OrganizationRepository organizations;
  private final MembershipRepository memberships;
  private final OrganizationAccessService accessService;
  private final Duration invitationTtl;

  public InvitationServiceImpl(
      InvitationRepository invitations,
      OrganizationRepository organizations,
      MembershipRepository memberships,
      OrganizationAccessService accessService,
      @Value("${app.invitations.ttl:P7D}") Duration invitationTtl) {
    this.invitations = invitations;
    this.organizations = organizations;
    this.memberships = memberships;
    this.accessService = accessService;
    this.invitationTtl = invitationTtl;
  }

  @Override
  @Transactional
  public InvitationResponse createInvitation(
      AuthenticatedPrincipal principal, UUID organizationId, String email, MembershipRole role) {
    MembershipRole callerRole = accessService.requireMembership(principal, organizationId);
    if (callerRole.compareTo(MembershipRole.ADMIN) < 0) {
      throw new AccessDeniedException("You cannot perform this action.");
    }
    if (role == MembershipRole.OWNER && callerRole != MembershipRole.OWNER) {
      throw new AccessDeniedException("Only an owner can invite a new owner.");
    }

    String rawToken = InvitationTokens.generate();
    String tokenHash = InvitationTokens.hash(rawToken);
    Instant expiresAt = Instant.now().plus(invitationTtl);

    Invitation invitation =
        invitations.save(
            new Invitation(
                organizationId,
                email.toLowerCase(Locale.ROOT),
                role,
                tokenHash,
                expiresAt,
                principal.userId()));

    return new InvitationResponse(
        invitation.getId(),
        invitation.getEmail(),
        invitation.getRole(),
        rawToken,
        invitation.getExpiresAt());
  }

  @Override
  @Transactional
  public AcceptInvitationResponse acceptInvitation(
      UUID userId, String verifiedEmail, String rawToken) {
    String tokenHash = InvitationTokens.hash(rawToken);
    Invitation invitation =
        invitations
            .findByTokenHash(tokenHash)
            .orElseThrow(() -> new NotFoundException(INVALID_INVITATION_MESSAGE));

    if (!invitation.getEmail().equalsIgnoreCase(verifiedEmail)) {
      throw new NotFoundException(INVALID_INVITATION_MESSAGE);
    }
    if (invitation.getAcceptedAt() != null) {
      throw new ConflictException("This invitation has already been used.");
    }
    if (invitation.getExpiresAt().isBefore(Instant.now())) {
      throw new ConflictException("This invitation has expired.");
    }
    if (memberships
        .findByOrganizationIdAndUserId(invitation.getOrganizationId(), userId)
        .isPresent()) {
      throw new ConflictException("You are already a member of this organization.");
    }

    memberships.save(new Membership(invitation.getOrganizationId(), userId, invitation.getRole()));
    invitation.markAccepted(Instant.now());
    invitations.save(invitation);

    Organization organization =
        organizations
            .findById(invitation.getOrganizationId())
            .orElseThrow(
                () -> new IllegalStateException("Invitation references a missing organization."));
    return new AcceptInvitationResponse(
        organization.getId(), organization.getName(), invitation.getRole());
  }
}
