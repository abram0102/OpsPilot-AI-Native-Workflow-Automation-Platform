import { apiClient, requireAccessToken, unwrap } from "@/lib/api/client";

/** Mirrors services/api's {@code MembershipRole} (docs/API_CONTRACT.md). */
export type MembershipRole = "VIEWER" | "MEMBER" | "ADMIN" | "OWNER";

export interface OrganizationMembership {
  organizationId: string;
  name: string;
  role: MembershipRole;
}

export interface Me {
  userId: string;
  email: string;
  displayName: string;
  organizations: OrganizationMembership[];
}

/**
 * Fetches the current user's profile and accessible organizations, through the typed client
 * (T11) generated from services/api's real `GET /api/v1/me` endpoint. Returns `null` when there is
 * no session (caller decides whether that means "redirect to login"). This is also where a
 * brand-new subject gets provisioned server-side (T08); every authenticated page load calls this
 * at least once.
 *
 * The generated response type marks every field optional — springdoc has no hint that these are
 * always present on a 200 (no `@Schema(requiredMode = REQUIRED)` was added; see
 * docs/DECISIONS.md ADR-033) — so this is the one place that asserts what the real contract
 * actually guarantees, rather than pushing `| undefined` into every page that reads `Me`.
 */
export async function fetchMe(): Promise<Me | null> {
  const token = await requireAccessToken();
  if (!token) {
    return null;
  }
  const result = await apiClient(token).GET("/api/v1/me");
  const data = await unwrap(result);
  return {
    userId: data.userId!,
    email: data.email!,
    displayName: data.displayName!,
    organizations: (data.organizations ?? []).map((org) => ({
      organizationId: org.organizationId!,
      name: org.name!,
      role: org.role!,
    })),
  };
}
