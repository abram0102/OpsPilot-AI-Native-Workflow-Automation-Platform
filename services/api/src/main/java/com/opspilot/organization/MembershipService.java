package com.opspilot.organization;

import com.opspilot.auth.AuthenticatedPrincipal;
import java.util.UUID;

/**
 * Role changes on an existing membership (docs/API_CONTRACT.md: {@code PATCH
 * /organizations/{orgId}/members/{memberId}}, "owner/admin w/ guard").
 */
public interface MembershipService {

  /**
   * @throws org.springframework.security.access.AccessDeniedException if the caller is below {@code
   *     ADMIN}, or is not {@code OWNER} while granting/already holding {@code OWNER}
   *     (docs/PRODUCT_AND_ARCHITECTURE.md U02: "Authorization prevents self-granting owner rights";
   *     ADMIN may not perform an owner transfer).
   * @throws com.opspilot.common.web.NotFoundException if {@code membershipId} does not belong to
   *     {@code organizationId}.
   * @throws com.opspilot.common.web.ConflictException if this would leave the organization with no
   *     {@code OWNER} (docs/DATA_MODEL.md: "last OWNER cannot be removed").
   */
  MemberResponse updateRole(
      AuthenticatedPrincipal principal,
      UUID organizationId,
      UUID membershipId,
      MembershipRole newRole);
}
