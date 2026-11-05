import { Suspense } from "react";

/**
 * Dashboard placeholder (docs/TASKS.md T04). Deliberately shows no ticket or
 * run counts: there is no backend connection yet (T08/T11), and
 * docs/PRODUCT_AND_ARCHITECTURE.md section 6 is explicit that mock data must
 * never be presented as live. Real counts arrive once T10 (tickets) and T27
 * (run explorer) wire this up to the actual API.
 *
 * `params` is read inside a child Suspense boundary rather than awaited at
 * the top of this component — see "Maximizing the static shell" in
 * node_modules/next/dist/docs/01-app/01-getting-started/08-caching.md. The
 * page shell renders immediately; only the org-specific line streams in.
 */
export default function DashboardPage({ params }: PageProps<"/app/[orgId]/dashboard">) {
  return (
    <div>
      <h1>Dashboard</h1>
      <Suspense fallback={<p>Loading organization…</p>}>
        <OrgSummary params={params} />
      </Suspense>
    </div>
  );
}

async function OrgSummary({ params }: Pick<PageProps<"/app/[orgId]/dashboard">, "params">) {
  const { orgId } = await params;

  return (
    <p>
      Organization <code>{orgId}</code> is not yet validated against real membership — that starts
      with tickets T08 and T09. Ticket and agent-run summaries will appear here once T10 and T27
      land; this placeholder intentionally shows no numbers rather than invented ones.
    </p>
  );
}
