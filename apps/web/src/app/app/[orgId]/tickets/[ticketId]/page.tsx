import { redirect } from "next/navigation";
import { fetchMe } from "@/lib/api/me";
import { getTicket, listTicketMessages } from "@/lib/api/tickets";
import { updateTicketAction, createMessageAction } from "./actions";
import { UpdateTicketForm } from "./UpdateTicketForm";
import { PostMessageForm } from "./PostMessageForm";

export const instant = false;

const REPLYABLE_STATUSES = new Set(["OPEN", "IN_PROGRESS"]);

/** Ticket detail + internal conversation (docs/TASKS.md T12). No "AI proposal"/"execution history"
    pane yet — docs/PRODUCT_AND_ARCHITECTURE.md section 6 describes those, but nothing backs them
    until the agent pipeline lands (T15+); showing empty stub panes ahead of that would be exactly
    the "mock data presented as live" this project's acceptance criteria forbid. */
export default async function TicketDetailPage({
  params,
}: PageProps<"/app/[orgId]/tickets/[ticketId]">) {
  const { orgId, ticketId } = await params;

  const me = await fetchMe();
  if (!me) {
    redirect("/sign-in");
  }
  const membership = me.organizations.find((org) => org.organizationId === orgId);
  if (!membership) {
    return (
      <main id="main-content" tabIndex={-1}>
        <h1>No access</h1>
        <p>You are not a member of this organization.</p>
      </main>
    );
  }
  const canManage = membership.role !== "VIEWER";

  const [ticket, messages] = await Promise.all([
    getTicket(orgId, ticketId),
    listTicketMessages(orgId, ticketId),
  ]);

  return (
    <div>
      <h1>{ticket.subject}</h1>
      <p>{ticket.description}</p>
      {ticket.tags.length > 0 && (
        <ul>
          {ticket.tags.map((tag) => (
            <li key={tag}>{tag}</li>
          ))}
        </ul>
      )}

      {canManage ? (
        <UpdateTicketForm
          action={updateTicketAction.bind(null, orgId, ticketId)}
          version={ticket.version}
          status={ticket.status}
          priority={ticket.priority}
        />
      ) : (
        <p>
          Status: {ticket.status} · Priority: {ticket.priority}
        </p>
      )}

      <h2>Conversation</h2>
      {messages.length === 0 ? (
        <p>No messages yet.</p>
      ) : (
        <ul>
          {messages.map((message) => (
            <li key={message.id}>
              <strong>{message.authorType === "AGENT" ? "Agent" : "User"}:</strong> {message.body}
            </li>
          ))}
        </ul>
      )}

      {canManage &&
        (REPLYABLE_STATUSES.has(ticket.status) ? (
          <PostMessageForm action={createMessageAction.bind(null, orgId, ticketId)} />
        ) : (
          <p>This ticket is {ticket.status} and cannot be replied to.</p>
        ))}
    </div>
  );
}
