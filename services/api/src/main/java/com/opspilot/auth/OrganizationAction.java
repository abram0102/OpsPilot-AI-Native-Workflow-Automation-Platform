package com.opspilot.auth;

import com.opspilot.organization.MembershipRole;

/**
 * Every organization-scoped action the API can authorize, each mapped to the minimum {@link
 * MembershipRole} required — the "role action permission matrix" docs/TASKS.md T07 asks for,
 * expressed as code rather than prose so it is the one place future tickets add a new action's
 * policy, and the one place {@link OrganizationAccessService} enforces it from (AGENTS.md rule 13:
 * server policy, not a presumed role).
 *
 * <p>Source: docs/PRODUCT_AND_ARCHITECTURE.md section 1 and docs/API_CONTRACT.md's per-endpoint
 * role column. Because {@link MembershipRole} is declared least-to-most privileged, "minimum role"
 * is enough to express the matrix — no action needs a non-contiguous role set.
 */
public enum OrganizationAction {
  VIEW_ORGANIZATION(MembershipRole.VIEWER),
  VIEW_TICKETS(MembershipRole.VIEWER),
  MANAGE_TICKETS(MembershipRole.MEMBER),
  VIEW_KNOWLEDGE(MembershipRole.VIEWER),
  UPLOAD_KNOWLEDGE(MembershipRole.MEMBER),
  DELETE_KNOWLEDGE(MembershipRole.ADMIN),
  RUN_AGENT(MembershipRole.MEMBER),
  /**
   * Reading runs, their steps and their usage — docs/API_CONTRACT.md gives these to member/viewer.
   */
  VIEW_RUNS(MembershipRole.VIEWER),
  DECIDE_APPROVAL(MembershipRole.ADMIN),
  MANAGE_MEMBERS(MembershipRole.ADMIN),
  MANAGE_INVITATIONS(MembershipRole.ADMIN),
  VIEW_AUDIT(MembershipRole.ADMIN);

  private final MembershipRole minimumRole;

  OrganizationAction(MembershipRole minimumRole) {
    this.minimumRole = minimumRole;
  }

  public MembershipRole minimumRole() {
    return minimumRole;
  }
}
