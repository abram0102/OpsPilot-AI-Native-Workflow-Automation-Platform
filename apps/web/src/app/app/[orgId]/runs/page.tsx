import { redirect } from "next/navigation";
import Link from "next/link";
import { fetchMe } from "@/lib/api/me";
import { listRuns, type AgentRunStatus } from "@/lib/api/runs";
import { FilterForm } from "@/components/ui/FilterForm";
import { StatusBadge } from "@/components/ui/StatusBadge";
import ui from "@/styles/ui.module.css";
import styles from "./page.module.css";

export const instant = false;

const STATUSES: AgentRunStatus[] = [
  "QUEUED",
  "RUNNING",
  "AWAITING_APPROVAL",
  "RETRY_SCHEDULED",
  "COMPLETED",
  "FAILED",
  "CANCELLED",
];

/**
 * The run explorer's index (docs/TASKS.md T27).
 *
 * <p>Nothing on this page is computed client-side from a partial page of data: the status filter
 * and the cursor are real server-side query parameters, and there is no total count because the
 * API deliberately does not compute one (ADR-030).
 */
export default async function RunsPage({ params, searchParams }: PageProps<"/app/[orgId]/runs">) {
  const { orgId } = await params;
  const { status, cursor } = await searchParams;

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

  const statusFilter = isRunStatus(status) ? status : undefined;
  const page = await listRuns(orgId, {
    status: statusFilter,
    cursor: typeof cursor === "string" ? cursor : undefined,
  });

  return (
    <div className={ui.page}>
      <div className={ui.pageHeaderText}>
        <h1>Agent runs</h1>
        <p className={ui.lead}>
          Every run the agent was asked to perform, including the ones that failed or were
          cancelled. A run never publishes anything by itself.
        </p>
      </div>

      <FilterForm label="Filter runs">
        <div className={ui.fieldInline}>
          <label htmlFor="run-status">Status</label>
          <select id="run-status" name="status" defaultValue={statusFilter ?? ""}>
            <option value="">All</option>
            {STATUSES.map((value) => (
              <option key={value} value={value}>
                {value.replaceAll("_", " ")}
              </option>
            ))}
          </select>
        </div>
      </FilterForm>

      {page.items.length === 0 ? (
        <div className={ui.empty}>
          <p>No runs match the current filter.</p>
          <p className={ui.hint}>A run is started from a ticket.</p>
        </div>
      ) : (
        <div className={ui.tableCard}>
          <div className={ui.tableScroll}>
            <table>
              <thead>
                <tr>
                  <th scope="col">Run</th>
                  <th scope="col">Status</th>
                  <th scope="col">Steps</th>
                  <th scope="col">Attempts</th>
                  <th scope="col">Started</th>
                </tr>
              </thead>
              <tbody>
                {page.items.map((run) => (
                  <tr key={run.id}>
                    <th scope="row" className={styles.runCell}>
                      <Link className={ui.mono} href={`/app/${orgId}/runs/${run.id}`}>
                        {run.id.slice(0, 8)}
                      </Link>
                      <span className={ui.hint}>{run.workflow.replaceAll("_", " ")}</span>
                    </th>
                    <td>
                      <StatusBadge value={run.status} />
                      {run.lastErrorCode && (
                        <span className={styles.errorCode}>{run.lastErrorCode}</span>
                      )}
                    </td>
                    <td className={styles.numberCell}>{run.stepsUsed}</td>
                    <td className={styles.numberCell}>{run.attemptCount}</td>
                    <td className={styles.timeCell}>{new Date(run.createdAt).toLocaleString()}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {page.nextCursor && (
        <div className={ui.actionsEnd}>
          <Link
            href={`/app/${orgId}/runs?${buildQuery({ status: statusFilter, cursor: page.nextCursor })}`}
          >
            Next page →
          </Link>
        </div>
      )}
    </div>
  );
}

function isRunStatus(value: unknown): value is AgentRunStatus {
  return typeof value === "string" && (STATUSES as string[]).includes(value);
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
