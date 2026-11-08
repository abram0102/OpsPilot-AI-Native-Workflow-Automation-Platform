import { redirect } from "next/navigation";
import Link from "next/link";
import { fetchMe } from "@/lib/api/me";
import { listTickets, type TicketPriority, type TicketStatus } from "@/lib/api/tickets";
import { createTicketAction } from "./actions";
import { CreateTicketDialog } from "./CreateTicketDialog";
import { TicketTable } from "./TicketTable";

export const instant = false;

const STATUSES: TicketStatus[] = ["OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED"];
const PRIORITIES: TicketPriority[] = ["LOW", "MEDIUM", "HIGH"];

/**
 * Ticket list: status/priority filters and keyset pagination are real server-side query params
 * (docs/API_CONTRACT.md); the "filter visible tickets" box is a client-side convenience over the
 * loaded page only (see {@link TicketTable}'s own doc comment for why). docs/TASKS.md T12.
 */
export default async function TicketsPage({
  params,
  searchParams,
}: PageProps<"/app/[orgId]/tickets">) {
  const { orgId } = await params;
  const { status, priority, cursor } = await searchParams;

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
  const canCreate = membership.role !== "VIEWER";

  const statusFilter = isTicketStatus(status) ? status : undefined;
  const priorityFilter = isTicketPriority(priority) ? priority : undefined;
  const cursorValue = typeof cursor === "string" ? cursor : undefined;

  const page = await listTickets(orgId, {
    status: statusFilter,
    priority: priorityFilter,
    cursor: cursorValue,
  });

  return (
    <div>
      <h1>Tickets</h1>

      <form method="get">
        <label htmlFor="filter-status">Status</label>
        <br />
        <select id="filter-status" name="status" defaultValue={statusFilter ?? ""}>
          <option value="">All</option>
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </select>
        <br />
        <label htmlFor="filter-priority">Priority</label>
        <br />
        <select id="filter-priority" name="priority" defaultValue={priorityFilter ?? ""}>
          <option value="">All</option>
          {PRIORITIES.map((p) => (
            <option key={p} value={p}>
              {p}
            </option>
          ))}
        </select>
        <br />
        <button type="submit">Apply filters</button>
      </form>

      {canCreate && <CreateTicketDialog action={createTicketAction.bind(null, orgId)} />}

      <TicketTable orgId={orgId} tickets={page.items} />

      {page.nextCursor && (
        <Link
          href={`/app/${orgId}/tickets?${buildQuery({ status: statusFilter, priority: priorityFilter, cursor: page.nextCursor })}`}
        >
          Next page
        </Link>
      )}
    </div>
  );
}

function isTicketStatus(value: unknown): value is TicketStatus {
  return typeof value === "string" && (STATUSES as string[]).includes(value);
}

function isTicketPriority(value: unknown): value is TicketPriority {
  return typeof value === "string" && (PRIORITIES as string[]).includes(value);
}

function buildQuery(params: Record<string, string | undefined>): string {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value) {
      query.set(key, value);
    }
  }
  return query.toString();
}
