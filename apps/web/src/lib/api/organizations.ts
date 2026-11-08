import { apiClient, requireAccessToken, unwrap } from "@/lib/api/client";
import type { MembershipRole } from "@/lib/api/me";

export type { MembershipRole } from "@/lib/api/me";
export { ApiError } from "@/lib/api/client";

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

async function authorizedClient() {
  const token = await requireAccessToken();
  if (!token) {
    throw new Error("Not signed in.");
  }
  return apiClient(token);
}

export async function createOrganization(
  name: string,
  slug: string,
): Promise<OrganizationDetail> {
  const client = await authorizedClient();
  const result = await client.POST("/api/v1/organizations", { body: { name, slug } });
  const data = await unwrap(result);
  return {
    id: data.id!,
    name: data.name!,
    slug: data.slug!,
    createdAt: data.createdAt!,
    myRole: data.myRole!,
  };
}

export async function fetchMembers(organizationId: string): Promise<Member[]> {
  const client = await authorizedClient();
  const result = await client.GET("/api/v1/organizations/{orgId}/members", {
    params: { path: { orgId: organizationId } },
  });
  const data = await unwrap(result);
  return data.map((member) => ({
    membershipId: member.membershipId!,
    userId: member.userId!,
    email: member.email!,
    displayName: member.displayName!,
    role: member.role!,
    memberSince: member.memberSince!,
  }));
}

export async function createInvitation(
  organizationId: string,
  email: string,
  role: MembershipRole,
): Promise<Invitation> {
  const client = await authorizedClient();
  const result = await client.POST("/api/v1/organizations/{orgId}/invitations", {
    params: { path: { orgId: organizationId } },
    body: { email, role },
  });
  const data = await unwrap(result);
  return {
    id: data.id!,
    email: data.email!,
    role: data.role!,
    token: data.token!,
    expiresAt: data.expiresAt!,
  };
}

export async function acceptInvitation(token: string): Promise<AcceptedInvitation> {
  const client = await authorizedClient();
  const result = await client.POST("/api/v1/invitations/accept", { body: { token } });
  const data = await unwrap(result);
  return { organizationId: data.organizationId!, organizationName: data.organizationName!, role: data.role! };
}

export async function updateMemberRole(
  organizationId: string,
  membershipId: string,
  role: MembershipRole,
): Promise<Member> {
  const client = await authorizedClient();
  const result = await client.PATCH("/api/v1/organizations/{orgId}/members/{memberId}", {
    params: { path: { orgId: organizationId, memberId: membershipId } },
    body: { role },
  });
  const data = await unwrap(result);
  return {
    membershipId: data.membershipId!,
    userId: data.userId!,
    email: data.email!,
    displayName: data.displayName!,
    role: data.role!,
    memberSince: data.memberSince!,
  };
}
