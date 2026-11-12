import Link from "next/link";
import { redirect } from "next/navigation";
import { fetchMe } from "@/lib/api/me";
import { getTicket, listTicketMessages } from "@/lib/api/tickets";
import { listRuns } from "@/lib/api/runs";
import { StatusBadge } from "@/components/ui/StatusBadge";
import ui from "@/styles/ui.module.css";
import { updateTicketAction, createMessageAction, startRunAction } from "./actions";
import { StartRunForm } from "./StartRunForm";
import { UpdateTicketForm } from "./UpdateTicketForm";
import { PostMessageForm } from "./PostMessageForm";
import styles from "./page.module.css";

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
      <main id="main-content" tabIndex={-1} className={ui.pageNarrow}>
        <h1>No access</h1>
        <p className={ui.lead}>You are not a member of this organization.</p>
      </main>
    );
  }
  const canManage = membership.role !== "VIEWER";

  const [ticket, messages, runs] = await Promise.all([
    getTicket(orgId, ticketId),
    listTicketMessages(orgId, ticketId),
    listRuns(orgId, { ticketId, limit: 5 }),
  ]);

  // Generated per render, not per click: a double-submitted form carries the same key, so the
  // backend returns the run it already created rather than starting a second one (T18).
  const startRunKey = crypto.randomUUID();

  return (
    <div className={ui.page}>
      <Link className={ui.backLink} href={`/app/${orgId}/tickets`}>
        ← Back to tickets
      </Link>

      <div className={ui.pageHeaderText}>
        <h1>{ticket.subject}</h1>
        <div className={ui.actions}>
          <StatusBadge value={ticket.status} />
          <StatusBadge value={ticket.priority} />
          {ticket.tags.map((tag) => (
            <span key={tag} className={ui.badge}>
              {tag}
            </span>
          ))}
        </div>
      </div>

      <div className={styles.columns}>
        <div className={styles.main}>
          <section className={ui.card}>
            <h2 className={ui.cardTitle}>Original request</h2>
            <p className={styles.description}>{ticket.description}</p>
          </section>

          <section className={ui.card}>
            <h2 className={ui.cardTitle}>Conversation</h2>
            {messages.length === 0 ? (
              <p className={ui.lead}>No messages yet.</p>
            ) : (
              <ul className={styles.thread}>
                {messages.map((message) => {
                  const fromAgent = message.authorType === "AGENT";
                  return (
                    <li
                      key={message.id}
                      className={fromAgent ? styles.messageAgent : styles.message}
                    >
                      <span className={styles.author}>{fromAgent ? "Agent" : "User"}</span>
                      <p className={styles.body}>{message.body}</p>
                    </li>
                  );
                })}
              </ul>
            )}

            {canManage &&
              (REPLYABLE_STATUSES.has(ticket.status) ? (
                <PostMessageForm action={createMessageAction.bind(null, orgId, ticketId)} />
              ) : (
                <p className={ui.alertNotice}>
                  This ticket is {ticket.status} and cannot be replied to.
                </p>
              ))}
          </section>
        </div>

        <aside className={styles.side}>
          {canManage ? (
            <UpdateTicketForm
              action={updateTicketAction.bind(null, orgId, ticketId)}
              version={ticket.version}
              status={ticket.status}
              priority={ticket.priority}
            />
          ) : (
            <div className={ui.card}>
              <h2 className={ui.cardTitle}>Properties</h2>
              <dl className={ui.metaList}>
                <div className={ui.metaItem}>
                  <dt>Status</dt>
                  <dd>
                    <StatusBadge value={ticket.status} />
                  </dd>
                </div>
                <div className={ui.metaItem}>
                  <dt>Priority</dt>
                  <dd>
                    <StatusBadge value={ticket.priority} />
                  </dd>
                </div>
              </dl>
              <p className={ui.hint}>Viewers cannot change a ticket.</p>
            </div>
          )}

          <section className={ui.card}>
            <div className={ui.sectionTitle}>
              <h2 className={ui.cardTitle}>Agent runs</h2>
            </div>

            {runs.items.length === 0 ? (
              <p className={ui.hint}>
                The agent has not been asked to work this ticket. A run drafts a reply and stops for
                a human decision — it never publishes anything by itself.
              </p>
            ) : (
              <ul className={styles.runList}>
                {runs.items.map((run) => (
                  <li key={run.id} className={styles.runItem}>
                    <Link className={ui.mono} href={`/app/${orgId}/runs/${run.id}`}>
                      {run.id.slice(0, 8)}
                    </Link>
                    <StatusBadge value={run.status} />
                  </li>
                ))}
              </ul>
            )}

            {canManage && (
              <StartRunForm
                action={startRunAction.bind(null, orgId, ticketId)}
                idempotencyKey={startRunKey}
              />
            )}
          </section>
        </aside>
      </div>
    </div>
  );
}
