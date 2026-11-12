import { Suspense } from "react";
import Link from "next/link";
import { redirect } from "next/navigation";
import { fetchMe } from "@/lib/api/me";
import { listTickets, type TicketStatus } from "@/lib/api/tickets";
import ui from "@/styles/ui.module.css";

export const instant = false;

const STATUSES: TicketStatus[] = ["OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED"];

/**
 * Dashboard (T04 placeholder, real counts since T12). docs/PRODUCT_AND_ARCHITECTURE.md section 6:
 * never invent numbers — the API deliberately never computes an exact total (docs/DECISIONS.md
 * ADR-030), so a bucket at the max page size shows "100+", never a precise count it doesn't
 * actually have.
 *
 * Re-checks the session/membership itself (same as every other `/app/[orgId]/*` page) rather than
 * relying only on the layout's redirect — without this, an unauthenticated request made the
 * `TicketCounts` Suspense child throw before the layout's own redirect took effect, a real bug
 * caught while manually verifying this page.
 */
export default async function DashboardPage({ params }: PageProps<"/app/[orgId]/dashboard">) {
  const { orgId } = await params;
  const me = await fetchMe();
  if (!me) {
    redirect("/sign-in");
  }
  if (!me.organizations.some((org) => org.organizationId === orgId)) {
    return (
      <main id="main-content" tabIndex={-1} className={ui.pageNarrow}>
        <h1>No access</h1>
        <p className={ui.lead}>You are not a member of this organization.</p>
      </main>
    );
  }

  return (
    <div className={ui.page}>
      <div className={ui.pageHeader}>
        <div className={ui.pageHeaderText}>
          <h1>Dashboard</h1>
          <p className={ui.lead}>
            Open tickets by status for this organization. Select a status to see the tickets behind
            it.
          </p>
        </div>
      </div>
      <Suspense fallback={<p className={ui.lead}>Loading ticket counts…</p>}>
        <TicketCounts orgId={orgId} />
      </Suspense>
    </div>
  );
}

async function TicketCounts({ orgId }: { orgId: string }) {
  const counts = await Promise.all(
    STATUSES.map(async (status) => {
      const page = await listTickets(orgId, { status, limit: 100 });
      const count = page.items.length;
      return { status, label: page.nextCursor ? `${count}+` : String(count) };
    }),
  );

  return (
    <dl className={ui.statGrid}>
      {counts.map(({ status, label }) => (
        <div key={status} className={ui.statTile}>
          <dt className={ui.statLabel}>
            <Link className={ui.stretchLink} href={`/app/${orgId}/tickets?status=${status}`}>
              {formatStatus(status)}
            </Link>
          </dt>
          <dd className={ui.statValue}>{label}</dd>
        </div>
      ))}
    </dl>
  );
}

function formatStatus(status: TicketStatus): string {
  return status.replaceAll("_", " ");
}
