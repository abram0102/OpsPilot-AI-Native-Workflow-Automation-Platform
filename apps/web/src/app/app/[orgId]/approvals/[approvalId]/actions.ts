"use server";

import { revalidatePath } from "next/cache";
import { ApiError, approveApproval, rejectApproval } from "@/lib/api/approvals";

export interface DecisionState {
  error?: string;
  /** Set when the approval was already decided by someone else — a conflict, not a failure. */
  conflict?: boolean;
  decided?: "APPROVED" | "REJECTED";
}

/**
 * Backs the approve and reject buttons, bound to {@code orgId}/{@code approvalId} by the page.
 *
 * <p>Carries no text. The proposal is immutable and the backend accepts no body on these
 * endpoints, so there is deliberately nothing here through which a different reply could travel.
 */
export async function decideApprovalAction(
  orgId: string,
  approvalId: string,
  outcome: "APPROVED" | "REJECTED",
  // Both are required by useActionState's call signature and both are deliberately unused: the
  // decision needs no prior state and carries no form input. That the FormData is ignored here is
  // the point, not an oversight — nothing a client submits can influence what gets published.
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  _prevState: DecisionState,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  _formData: FormData,
): Promise<DecisionState> {
  try {
    const approval =
      outcome === "APPROVED"
        ? await approveApproval(orgId, approvalId)
        : await rejectApproval(orgId, approvalId);

    revalidatePath(`/app/${orgId}/approvals`);
    revalidatePath(`/app/${orgId}/approvals/${approvalId}`);
    return { decided: approval.status === "APPROVED" ? "APPROVED" : "REJECTED" };
  } catch (error) {
    if (error instanceof ApiError) {
      // 409 means a decision already exists — most likely another reviewer got there first. That
      // is worth saying plainly rather than showing as a generic failure, because the reviewer's
      // next step is to re-read the decision, not to retry.
      if (error.status === 409) {
        revalidatePath(`/app/${orgId}/approvals/${approvalId}`);
        return {
          conflict: true,
          error: "This approval has already been decided. Reload to see the current decision.",
        };
      }
      return { error: error.message };
    }
    throw error;
  }
}
