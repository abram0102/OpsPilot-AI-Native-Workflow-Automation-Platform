"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { startRun } from "@/lib/api/runs";
import {
  ApiError,
  createTicketMessage,
  updateTicket,
  type TicketPriority,
  type TicketStatus,
} from "@/lib/api/tickets";

export interface UpdateTicketState {
  error?: string;
}

/**
 * Backs the status/priority edit form. `version` is a hidden field carrying the value the page
 * last rendered — a stale one comes back as the backend's own collision message
 * (docs/DECISIONS.md ADR-029/ADR-032), shown as-is rather than a generic error.
 */
export async function updateTicketAction(
  orgId: string,
  ticketId: string,
  _prevState: UpdateTicketState,
  formData: FormData,
): Promise<UpdateTicketState> {
  const version = Number(formData.get("version"));
  const status = String(formData.get("status") ?? "") as TicketStatus;
  const priority = String(formData.get("priority") ?? "") as TicketPriority;

  try {
    await updateTicket(orgId, ticketId, version, { status, priority });
  } catch (error) {
    if (error instanceof ApiError) {
      return { error: error.message };
    }
    throw error;
  }

  revalidatePath(`/app/${orgId}/tickets/${ticketId}`);
  return {};
}

export interface PostMessageState {
  error?: string;
}

export async function createMessageAction(
  orgId: string,
  ticketId: string,
  _prevState: PostMessageState,
  formData: FormData,
): Promise<PostMessageState> {
  const body = String(formData.get("body") ?? "").trim();
  if (!body) {
    return { error: "A message body is required." };
  }

  try {
    await createTicketMessage(orgId, ticketId, body);
  } catch (error) {
    if (error instanceof ApiError) {
      return { error: error.message };
    }
    throw error;
  }

  revalidatePath(`/app/${orgId}/tickets/${ticketId}`);
  return {};
}

export interface StartRunState {
  error?: string;
}

/**
 * Asks the agent to work this ticket, then sends the user to the new run's page.
 *
 * <p>The idempotency key comes from the form, generated once when the page rendered. That is the
 * real duplicate-click protection: a second submission carries the *same* key, so services/api
 * returns the run it already created instead of starting a second one (T18). A disabled button
 * only narrows the window; the key closes it, including across a double-submit that races.
 *
 * <p>The redirect is what makes the flow legible — a run that has only been accepted has nothing
 * to show on the ticket, and its page is where progress appears.
 */
export async function startRunAction(
  orgId: string,
  ticketId: string,
  _prevState: StartRunState,
  formData: FormData,
): Promise<StartRunState> {
  const idempotencyKey = String(formData.get("idempotencyKey") ?? "").trim();
  if (!idempotencyKey) {
    return { error: "Missing request key. Reload the page and try again." };
  }

  let runId: string;
  try {
    const started = await startRun(orgId, ticketId, idempotencyKey);
    runId = started.runId;
  } catch (error) {
    if (error instanceof ApiError) {
      // 409 here means this key was already used for a *different* request body — a genuine
      // client bug, not a duplicate of this submission, so it is not silently absorbed.
      return { error: error.message };
    }
    throw error;
  }

  revalidatePath(`/app/${orgId}/tickets/${ticketId}`);
  revalidatePath(`/app/${orgId}/runs`);
  redirect(`/app/${orgId}/runs/${runId}`);
}
