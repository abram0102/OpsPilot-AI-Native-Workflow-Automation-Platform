import { apiClient, requireAccessToken, unwrap } from "@/lib/api/client";

export { ApiError } from "@/lib/api/client";

export type ApprovalStatus = "PENDING" | "APPROVED" | "REJECTED" | "EXPIRED";
export type AgentActionType = "ADD_INTERNAL_TAG" | "PUBLISH_INTERNAL_REPLY";

/** One chunk the draft cited — enough to find the source, not a copy of it. */
export interface ApprovalEvidence {
  documentId: string;
  chunkIndex?: number;
  title?: string;
}

/** Mirrors services/api's {@code ApprovalResponse}. */
export interface Approval {
  id: string;
  runId: string;
  ticketId: string;
  toolCallId: string;
  actionType: AgentActionType;
  status: ApprovalStatus;
  /** The exact text a human is being asked to approve. Never sent back when deciding. */
  proposalBody?: string;
  evidence: ApprovalEvidence[];
  decisionBy?: string;
  decisionAt?: string;
  createdAt: string;
  updatedAt: string;
}

async function authorizedClient() {
  const token = await requireAccessToken();
  if (!token) {
    throw new Error("Not signed in.");
  }
  return apiClient(token);
}

function toApproval(data: {
  id?: string;
  runId?: string;
  ticketId?: string;
  toolCallId?: string;
  actionType?: AgentActionType;
  status?: ApprovalStatus;
  proposal?: { body?: string };
  evidence?: { documentId?: string; chunkIndex?: number; title?: string }[];
  decisionBy?: string;
  decisionAt?: string;
  createdAt?: string;
  updatedAt?: string;
}): Approval {
  return {
    id: data.id!,
    runId: data.runId!,
    ticketId: data.ticketId!,
    toolCallId: data.toolCallId!,
    actionType: data.actionType!,
    status: data.status!,
    proposalBody: data.proposal?.body,
    evidence: (data.evidence ?? []).map((item) => ({
      documentId: item.documentId!,
      chunkIndex: item.chunkIndex,
      title: item.title,
    })),
    decisionBy: data.decisionBy,
    decisionAt: data.decisionAt,
    createdAt: data.createdAt!,
    updatedAt: data.updatedAt!,
  };
}

export async function listApprovals(
  organizationId: string,
  status?: ApprovalStatus,
): Promise<Approval[]> {
  const client = await authorizedClient();
  const result = await client.GET("/api/v1/organizations/{orgId}/approvals", {
    params: { path: { orgId: organizationId }, query: status ? { status } : {} },
  });
  return (await unwrap(result)).map(toApproval);
}

export async function getApproval(organizationId: string, approvalId: string): Promise<Approval> {
  const client = await authorizedClient();
  const result = await client.GET("/api/v1/organizations/{orgId}/approvals/{approvalId}", {
    params: { path: { orgId: organizationId, approvalId } },
  });
  return toApproval(await unwrap(result));
}

/**
 * Approves the proposal exactly as stored.
 *
 * <p>Takes no text, on purpose. The backend accepts no body here either — the point of the gate is
 * that the approved text and the published text are the same string, so there is nowhere in this
 * call for a different one to travel.
 */
export async function approveApproval(
  organizationId: string,
  approvalId: string,
): Promise<Approval> {
  const client = await authorizedClient();
  const result = await client.POST("/api/v1/organizations/{orgId}/approvals/{approvalId}/approve", {
    params: { path: { orgId: organizationId, approvalId } },
  });
  return toApproval(await unwrap(result));
}

export async function rejectApproval(
  organizationId: string,
  approvalId: string,
): Promise<Approval> {
  const client = await authorizedClient();
  const result = await client.POST("/api/v1/organizations/{orgId}/approvals/{approvalId}/reject", {
    params: { path: { orgId: organizationId, approvalId } },
  });
  return toApproval(await unwrap(result));
}
