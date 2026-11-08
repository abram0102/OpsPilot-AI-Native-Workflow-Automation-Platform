import { auth0 } from "@/lib/auth0";
import { getApiBaseUrl } from "@/lib/api/config";
import type { OrganizationMembership } from "@/lib/api/me";

/** Mirrors services/api's {@code MembershipRole} (docs/API_CONTRACT.md). */
export type MembershipRole = OrganizationMembership["role"];

/** Mirrors services/api's {@code OrganizationResponse}. */
export interface OrganizationDetail {
  id: string;
  name: string;
  slug: string;
  createdAt: string;
  myRole: MembershipRole;
}

/** Mirrors services/api's {@code MemberResponse}. */
export interface Member {
  membershipId: string;
  userId: string;
  email: string;
  displayName: string;
  role: MembershipRole;
  memberSince: string;
}

/** Mirrors services/api's {@code InvitationResponse}. {@code token} is shown exactly once. */
export interface Invitation {
  id: string;
  email: string;
  role: MembershipRole;
  token: string;
  expiresAt: string;
}

/** Mirrors services/api's {@code AcceptInvitationResponse}. */
export interface AcceptedInvitation {
  organizationId: string;
  organizationName: string;
  role: MembershipRole;
}

/** A non-2xx response from the API, carrying the stable error envelope's code and message. */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

async function accessToken(): Promise<string> {
  const session = await auth0?.getSession();
  if (!session) {
    throw new Error("Not signed in.");
  }
  const { token } = await auth0!.getAccessToken();
  return token;
}

/**
 * Forwards the BFF's own access token to the Spring API and parses the stable error envelope
 * (docs/API_CONTRACT.md) into an {@link ApiError} on failure, so server actions can show a caller
 * what went wrong instead of a generic framework error page.
 */
async function apiRequest<T>(path: string, init?: RequestInit): Promise<T> {
  const token = await accessToken();
  const response = await fetch(`${getApiBaseUrl()}${path}`, {
    ...init,
    headers: {
      ...(init?.body ? { "Content-Type": "application/json" } : {}),
      ...init?.headers,
      Authorization: `Bearer ${token}`,
    },
    cache: "no-store",
  });

  if (!response.ok) {
    let code = "UNKNOWN";
    let message = `Request failed with status ${response.status}.`;
    try {
      const body = (await response.json()) as { error?: { code?: string; message?: string } };
      code = body.error?.code ?? code;
      message = body.error?.message ?? message;
    } catch {
      // Non-JSON error body — fall back to the generic message above.
    }
    throw new ApiError(response.status, code, message);
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

export async function createOrganization(
  name: string,
  slug: string,
): Promise<OrganizationDetail> {
  return apiRequest<OrganizationDetail>("/api/v1/organizations", {
    method: "POST",
    body: JSON.stringify({ name, slug }),
  });
}

export async function fetchMembers(organizationId: string): Promise<Member[]> {
  return apiRequest<Member[]>(`/api/v1/organizations/${organizationId}/members`);
}

export async function createInvitation(
  organizationId: string,
  email: string,
  role: MembershipRole,
): Promise<Invitation> {
  return apiRequest<Invitation>(`/api/v1/organizations/${organizationId}/invitations`, {
    method: "POST",
    body: JSON.stringify({ email, role }),
  });
}

export async function acceptInvitation(token: string): Promise<AcceptedInvitation> {
  return apiRequest<AcceptedInvitation>("/api/v1/invitations/accept", {
    method: "POST",
    body: JSON.stringify({ token }),
  });
}

export async function updateMemberRole(
  organizationId: string,
  membershipId: string,
  role: MembershipRole,
): Promise<Member> {
  return apiRequest<Member>(
    `/api/v1/organizations/${organizationId}/members/${membershipId}`,
    { method: "PATCH", body: JSON.stringify({ role }) },
  );
}
