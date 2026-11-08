"use server";

import { revalidatePath } from "next/cache";
import {
  ApiError,
  createInvitation,
  updateMemberRole,
  type MembershipRole,
} from "@/lib/api/organizations";

export interface InviteMemberState {
  error?: string;
  invitedEmail?: string;
  token?: string;
}

/** Backs {@link InviteMemberForm}. Bound to {@code orgId} by the server component that renders it. */
export async function createInvitationAction(
  orgId: string,
  _prevState: InviteMemberState,
  formData: FormData,
): Promise<InviteMemberState> {
  const email = String(formData.get("email") ?? "").trim();
  const role = String(formData.get("role") ?? "") as MembershipRole;

  if (!email || !role) {
    return { error: "Email and role are both required." };
  }

  try {
    const invitation = await createInvitation(orgId, email, role);
    return { invitedEmail: invitation.email, token: invitation.token };
  } catch (error) {
    if (error instanceof ApiError) {
      return { error: error.message };
    }
    throw error;
  }
}

/**
 * Backs each member row's role-update form, bound to {@code orgId}/{@code membershipId} by the
 * server component. The backend enforces last-owner and owner-transfer guards regardless
 * (AGENTS.md rule 11); a thrown {@link ApiError} here surfaces via the nearest error boundary.
 */
export async function updateMemberRoleAction(
  orgId: string,
  membershipId: string,
  formData: FormData,
): Promise<void> {
  const role = String(formData.get("role") ?? "") as MembershipRole;
  await updateMemberRole(orgId, membershipId, role);
  revalidatePath(`/app/${orgId}/settings/members`);
}
