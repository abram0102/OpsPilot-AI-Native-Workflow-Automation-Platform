"use server";

import { redirect } from "next/navigation";
import { ApiError, createTicket, type TicketPriority } from "@/lib/api/tickets";

export interface CreateTicketState {
  error?: string;
}

/** Backs {@link CreateTicketDialog}. Bound to {@code orgId} by the server component that renders it. */
export async function createTicketAction(
  orgId: string,
  _prevState: CreateTicketState,
  formData: FormData,
): Promise<CreateTicketState> {
  const customerName = String(formData.get("customerName") ?? "").trim();
  const subject = String(formData.get("subject") ?? "").trim();
  const description = String(formData.get("description") ?? "").trim();
  const priority = String(formData.get("priority") ?? "") as TicketPriority | "";

  if (!customerName || !subject || !description) {
    return { error: "Customer, subject and description are all required." };
  }

  let ticketId: string;
  try {
    const ticket = await createTicket(orgId, {
      customerName,
      subject,
      description,
      priority: priority || undefined,
    });
    ticketId = ticket.id;
  } catch (error) {
    if (error instanceof ApiError) {
      return { error: error.message };
    }
    throw error;
  }

  redirect(`/app/${orgId}/tickets/${ticketId}`);
}
