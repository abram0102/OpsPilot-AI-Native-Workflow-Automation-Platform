import { redirect } from "next/navigation";
import Link from "next/link";
import { fetchMe } from "@/lib/api/me";
import { listTickets, type TicketPriority, type TicketStatus } from "@/lib/api/tickets";
import { FilterForm } from "@/components/ui/FilterForm";
import ui from "@/styles/ui.module.css";
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
      <main id="main-content" tabIndex={-1} className={ui.pageNarrow}>
        <h1>No access</h1>
        <p className={ui.lead}>You are not a member of this organization.</p>
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
    <div className={ui.page}>
      <div className={ui.pageHeader}>
        <div className={ui.pageHeaderText}>
          <h1>Tickets</h1>
          <p className={ui.lead}>
            Support conversations in this organization, newest activity first.
          </p>
        </div>
        {canCreate && <CreateTicketDialog action={createTicketAction.bind(null, orgId)} />}
      </div>

      <FilterForm label="Filter tickets">
        <div className={ui.fieldInline}>
          <label htmlFor="filter-status">Status</label>
          <select id="filter-status" name="status" defaultValue={statusFilter ?? ""}>
            <option value="">All</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </div>

        <div className={ui.fieldInline}>
          <label htmlFor="filter-priority">Priority</label>
          <select id="filter-priority" name="priority" defaultValue={priorityFilter ?? ""}>
            <option value="">All</option>
            {PRIORITIES.map((p) => (
              <option key={p} value={p}>
                {p}
              </option>
            ))}
          </select>
        </div>
      </FilterForm>

      <TicketTable orgId={orgId} tickets={page.items} />

      {page.nextCursor && (
        <div className={ui.actionsEnd}>
          <Link
            href={`/app/${orgId}/tickets?${buildQuery({ status: statusFilter, priority: priorityFilter, cursor: page.nextCursor })}`}
          >
            Next page →
          </Link>
        </div>
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
