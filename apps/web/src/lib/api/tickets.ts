import { apiClient, requireAccessToken, unwrap } from "@/lib/api/client";

export type TicketStatus = "OPEN" | "IN_PROGRESS" | "RESOLVED" | "CLOSED";
export type TicketPriority = "LOW" | "MEDIUM" | "HIGH";

/** Mirrors services/api's {@code TicketResponse}. */
export interface Ticket {
  id: string;
  organizationId: string;
  customerId: string;
  subject: string;
  description: string;
  status: TicketStatus;
  priority: TicketPriority;
  assigneeId?: string;
  createdBy: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

/** Mirrors services/api's {@code TicketDetailResponse}. */
export interface TicketDetail extends Ticket {
  tags: string[];
  messageCount: number;
}

/** Mirrors services/api's {@code TicketPageResponse} (docs/API_CONTRACT.md's list envelope). */
export interface TicketPage {
  items: Ticket[];
  nextCursor?: string;
}

/** Mirrors services/api's {@code TicketMessageResponse}. */
export interface TicketMessage {
  id: string;
  authorType: "USER" | "AGENT";
  authorId?: string;
  body: string;
  publishedBy?: string;
  sourceRunId?: string;
  createdAt: string;
}

export interface CreateTicketInput {
  customerId?: string;
  customerName?: string;
  customerEmail?: string;
  subject: string;
  description: string;
  priority?: TicketPriority;
}

export interface ListTicketsFilter {
  status?: TicketStatus;
  priority?: TicketPriority;
  cursor?: string;
  limit?: number;
}

async function authorizedClient() {
  const token = await requireAccessToken();
  if (!token) {
    throw new Error("Not signed in.");
  }
  return apiClient(token);
}

function toTicket(data: {
  id?: string;
  organizationId?: string;
  customerId?: string;
  subject?: string;
  description?: string;
  status?: TicketStatus;
  priority?: TicketPriority;
  assigneeId?: string;
  createdBy?: string;
  version?: number;
  createdAt?: string;
  updatedAt?: string;
}): Ticket {
  return {
    id: data.id!,
    organizationId: data.organizationId!,
    customerId: data.customerId!,
    subject: data.subject!,
    description: data.description!,
    status: data.status!,
    priority: data.priority!,
    assigneeId: data.assigneeId,
    createdBy: data.createdBy!,
    version: data.version!,
    createdAt: data.createdAt!,
    updatedAt: data.updatedAt!,
  };
}

/** Creates a ticket (optionally creating its customer inline) through the typed client (T11). */
export async function createTicket(
  organizationId: string,
  input: CreateTicketInput,
): Promise<Ticket> {
  const client = await authorizedClient();
  const result = await client.POST("/api/v1/organizations/{orgId}/tickets", {
    params: { path: { orgId: organizationId } },
    body: input,
  });
  return toTicket(await unwrap(result));
}

export async function listTickets(
  organizationId: string,
  filter: ListTicketsFilter = {},
): Promise<TicketPage> {
  const client = await authorizedClient();
  const result = await client.GET("/api/v1/organizations/{orgId}/tickets", {
    params: {
      path: { orgId: organizationId },
      query: filter,
    },
  });
  const data = await unwrap(result);
  return {
    items: (data.items ?? []).map(toTicket),
    nextCursor: data.nextCursor,
  };
}

export async function getTicket(organizationId: string, ticketId: string): Promise<TicketDetail> {
  const client = await authorizedClient();
  const result = await client.GET("/api/v1/organizations/{orgId}/tickets/{ticketId}", {
    params: { path: { orgId: organizationId, ticketId } },
  });
  const data = await unwrap(result);
  return {
    ...toTicket(data),
    tags: data.tags ?? [],
    messageCount: data.messageCount ?? 0,
  };
}

export async function updateTicket(
  organizationId: string,
  ticketId: string,
  version: number,
  changes: { status?: TicketStatus; priority?: TicketPriority; assigneeId?: string },
): Promise<Ticket> {
  const client = await authorizedClient();
  const result = await client.PATCH("/api/v1/organizations/{orgId}/tickets/{ticketId}", {
    params: { path: { orgId: organizationId, ticketId } },
    body: { version, ...changes },
  });
  return toTicket(await unwrap(result));
}

export async function listTicketMessages(
  organizationId: string,
  ticketId: string,
): Promise<TicketMessage[]> {
  const client = await authorizedClient();
  const result = await client.GET("/api/v1/organizations/{orgId}/tickets/{ticketId}/messages", {
    params: { path: { orgId: organizationId, ticketId } },
  });
  const data = await unwrap(result);
  return data.map((message) => ({
    id: message.id!,
    authorType: message.authorType!,
    authorId: message.authorId,
    body: message.body!,
    publishedBy: message.publishedBy,
    sourceRunId: message.sourceRunId,
    createdAt: message.createdAt!,
  }));
}

export async function createTicketMessage(
  organizationId: string,
  ticketId: string,
  body: string,
): Promise<TicketMessage> {
  const client = await authorizedClient();
  const result = await client.POST("/api/v1/organizations/{orgId}/tickets/{ticketId}/messages", {
    params: { path: { orgId: organizationId, ticketId } },
    body: { body },
  });
  const data = await unwrap(result);
  return {
    id: data.id!,
    authorType: data.authorType!,
    authorId: data.authorId,
    body: data.body!,
    publishedBy: data.publishedBy,
    sourceRunId: data.sourceRunId,
    createdAt: data.createdAt!,
  };
}
