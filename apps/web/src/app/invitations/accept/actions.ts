"use server";

import { redirect } from "next/navigation";
import { ApiError, acceptInvitation } from "@/lib/api/organizations";

export interface AcceptInvitationState {
  error?: string;
}

/** Backs {@link AcceptInvitationForm}. Redirects to the joined org's dashboard on success. */
export async function acceptInvitationAction(
  _prevState: AcceptInvitationState,
  formData: FormData,
): Promise<AcceptInvitationState> {
  const token = String(formData.get("token") ?? "").trim();
  if (!token) {
    return { error: "An invitation token is required." };
  }

  let organizationId: string;
  try {
    const accepted = await acceptInvitation(token);
    organizationId = accepted.organizationId;
  } catch (error) {
    if (error instanceof ApiError) {
      return { error: error.message };
    }
    throw error;
  }

  redirect(`/app/${organizationId}/dashboard`);
}
