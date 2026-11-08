"use server";

import { revalidatePath } from "next/cache";
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
