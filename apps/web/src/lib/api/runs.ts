import { apiClient, requireAccessToken, unwrap } from "@/lib/api/client";
import type { components } from "@contracts/schema";

export { ApiError } from "@/lib/api/client";

/*
 * These unions are DERIVED from the generated contract rather than written out, so a new run state
 * or step type on the server becomes a TypeScript error here instead of a silent mismatch. Written
 * by hand first, and they were wrong: "SUCCEEDED" and "PLAN"/"DRAFT"/"TOOL_CALL" never existed —
 * the real states are COMPLETED/RETRY_SCHEDULED and CLASSIFY/DRAFT_REPLY/PROPOSE_TOOL/EXECUTE_TOOL.
 * Typechecking caught it, which is exactly what packages/contracts is for.
 */
export type AgentRunStatus = NonNullable<components["schemas"]["AgentRunDetailResponse"]["status"]>;

export type Workflow = NonNullable<components["schemas"]["AgentRunDetailResponse"]["workflow"]>;

export type AgentStepType = NonNullable<components["schemas"]["AgentStepResponse"]["stepType"]>;

export type AgentStepStatus = NonNullable<components["schemas"]["AgentStepResponse"]["status"]>;

/**
 * A run has reached a state nothing will move it out of. The explorer uses this to decide when to
 * stop polling — asking the server again after a run is terminal is a request that can only ever
 * return the same row.
 */
const TERMINAL_STATUSES: ReadonlySet<AgentRunStatus> = new Set([
  "COMPLETED",
  "FAILED",
  "CANCELLED",
]);

export function isTerminalRunStatus(status: AgentRunStatus): boolean {
  return TERMINAL_STATUSES.has(status);
}

/** Mirrors services/api's {@code AgentRunResponse} — a run as the explorer lists it. */
export interface AgentRun {
  id: string;
  ticketId: string;
  workflow: Workflow;
  status: AgentRunStatus;
  attemptCount: number;
  stepsUsed: number;
  lastErrorCode?: string;
  createdAt: string;
  updatedAt: string;
}

/**
 * Mirrors {@code AgentRunDetailResponse}. Token totals are what the provider actually reported;
 * there is deliberately no cost field, because the API exposes none — a figure labelled as spend
 * on this page would read as billed (ADR-041).
 */
export interface AgentRunDetail extends AgentRun {
  initiatedBy?: string;
  totalInputTokens: number;
  totalOutputTokens: number;
}

/** Mirrors {@code AgentStepResponse}: the sanitized trace, as stored. */
export interface AgentStep {
  id: string;
  sequence: number;
  stepType: AgentStepType;
  status: AgentStepStatus;
  sanitizedInput?: string;
  sanitizedOutput?: string;
  durationMs?: number;
  model?: string;
  tokensIn?: number;
  tokensOut?: number;
  errorCode?: string;
  createdAt: string;
}

export interface AgentRunPage {
  items: AgentRun[];
  nextCursor?: string;
}

export interface AgentStepPage {
  items: AgentStep[];
  nextCursor?: string;
}

async function authorizedClient() {
  const token = await requireAccessToken();
  if (!token) {
    throw new Error("Not signed in.");
  }
  return apiClient(token);
}

export async function listRuns(
  organizationId: string,
  options: {
    status?: AgentRunStatus;
    /** Narrows to one ticket's runs, filtered inside the query alongside the tenant. */
    ticketId?: string;
    cursor?: string;
    limit?: number;
  } = {},
): Promise<AgentRunPage> {
  const client = await authorizedClient();
  const result = await client.GET("/api/v1/organizations/{orgId}/runs", {
    params: {
      path: { orgId: organizationId },
      query: {
        ...(options.status ? { status: options.status } : {}),
        ...(options.ticketId ? { ticketId: options.ticketId } : {}),
        ...(options.cursor ? { cursor: options.cursor } : {}),
        ...(options.limit ? { limit: options.limit } : {}),
      },
    },
  });
  const page = await unwrap(result);
  return {
    items: (page.items ?? []) as AgentRun[],
    nextCursor: page.nextCursor,
  };
}

export async function getRun(organizationId: string, runId: string): Promise<AgentRunDetail> {
  const client = await authorizedClient();
  const result = await client.GET("/api/v1/organizations/{orgId}/runs/{runId}", {
    params: { path: { orgId: organizationId, runId } },
  });
  return (await unwrap(result)) as AgentRunDetail;
}

export async function listRunSteps(
  organizationId: string,
  runId: string,
  options: { cursor?: string; limit?: number } = {},
): Promise<AgentStepPage> {
  const client = await authorizedClient();
  const result = await client.GET("/api/v1/organizations/{orgId}/runs/{runId}/steps", {
    params: {
      path: { orgId: organizationId, runId },
      query: {
        ...(options.cursor ? { cursor: options.cursor } : {}),
        ...(options.limit ? { limit: options.limit } : {}),
      },
    },
  });
  const page = await unwrap(result);
  return {
    items: (page.items ?? []) as AgentStep[],
    nextCursor: page.nextCursor,
  };
}

/**
 * Requests a run. Returns `202` with the run `QUEUED` — accepted and durably recorded, not
 * executed, so nothing here may be reported as "the agent ran".
 *
 * <p>`idempotencyKey` is forwarded because a double-submitted form must not produce two runs for
 * one ticket; the backend keys on it (T18).
 */
export async function startRun(
  organizationId: string,
  ticketId: string,
  idempotencyKey: string,
  workflow: Workflow = "SUPPORT_TRIAGE_V1",
): Promise<{ runId: string; status: AgentRunStatus }> {
  const client = await authorizedClient();
  const result = await client.POST("/api/v1/organizations/{orgId}/tickets/{ticketId}/runs", {
    params: {
      path: { orgId: organizationId, ticketId },
      header: { "Idempotency-Key": idempotencyKey },
    },
    body: { workflow },
  });
  const started = await unwrap(result);
  return { runId: started.runId!, status: started.status as AgentRunStatus };
}

export async function cancelRun(organizationId: string, runId: string): Promise<AgentRunDetail> {
  const client = await authorizedClient();
  const result = await client.POST("/api/v1/organizations/{orgId}/runs/{runId}/cancel", {
    params: { path: { orgId: organizationId, runId } },
  });
  return (await unwrap(result)) as AgentRunDetail;
}
