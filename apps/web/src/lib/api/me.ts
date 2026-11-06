import { auth0 } from "@/lib/auth0";
import { getApiBaseUrl } from "@/lib/api/config";

/** Mirrors services/api's `MeResponse` (docs/API_CONTRACT.md: `GET /api/v1/me`). */
export interface OrganizationMembership {
  organizationId: string;
  name: string;
  role: "VIEWER" | "MEMBER" | "ADMIN" | "OWNER";
}

export interface Me {
  userId: string;
  email: string;
  displayName: string;
  organizations: OrganizationMembership[];
}

/**
 * Fetches the current user's profile and accessible organizations, forwarding
 * the BFF's own access token — the browser never sees it. Returns `null` when
 * there is no session (caller decides whether that means "redirect to login").
 * This is also where a brand-new subject gets provisioned server-side (T08);
 * every authenticated page load calls this at least once.
 */
export async function fetchMe(): Promise<Me | null> {
  const session = await auth0?.getSession();
  if (!session) {
    return null;
  }

  const { token } = await auth0!.getAccessToken();
  const response = await fetch(`${getApiBaseUrl()}/api/v1/me`, {
    headers: { Authorization: `Bearer ${token}` },
    cache: "no-store",
  });

  if (!response.ok) {
    throw new Error(`GET /api/v1/me failed with ${response.status}`);
  }

  return (await response.json()) as Me;
}
