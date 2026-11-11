import { redirect } from "next/navigation";
import Link from "next/link";
import { fetchMe } from "@/lib/api/me";
import { listApprovals, type ApprovalStatus } from "@/lib/api/approvals";

export const instant = false;

const STATUSES: ApprovalStatus[] = ["PENDING", "APPROVED", "REJECTED", "EXPIRED"];

/**
 * The approval inbox (docs/TASKS.md T22).
 *
 * <p>Owner/admin only, per docs/API_CONTRACT.md — this page lists what the agent proposes to say
 * to customers, which is a narrower audience than everyone who can read tickets. The backend
 * enforces it regardless; `canDecide` below only decides what to render.
 */
export default async function ApprovalsPage({
  params,
  searchParams,
}: PageProps<"/app/[orgId]/approvals">) {
  const { orgId } = await params;
  const { status } = await searchParams;

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

  const canDecide = membership.role === "ADMIN" || membership.role === "OWNER";
  if (!canDecide) {
    return (
      <div>
        <h1>Approvals</h1>
        <p>
          Only owners and admins can review the agent&apos;s proposed replies. Ask an administrator
          if you need access.
        </p>
      </div>
    );
  }

  const statusFilter = isApprovalStatus(status) ? status : undefined;
  const approvals = await listApprovals(orgId, statusFilter);

  return (
    <div>
      <h1>Approvals</h1>
      <p>
        The agent never publishes a reply on its own. Each proposal below is exactly what would be
        posted, and nothing is sent until someone approves it.
      </p>

      <form method="get">
        <label htmlFor="approval-status">Status</label>
        <br />
        <select id="approval-status" name="status" defaultValue={statusFilter ?? "PENDING"}>
          {STATUSES.map((value) => (
            <option key={value} value={value}>
              {value}
            </option>
          ))}
        </select>
        <br />
        <button type="submit">Apply filter</button>
      </form>

      {approvals.length === 0 ? (
        <p>Nothing is waiting for a decision.</p>
      ) : (
        <table>
          <caption>Proposals awaiting review</caption>
          <thead>
            <tr>
              <th scope="col">Proposed reply</th>
              <th scope="col">Action</th>
              <th scope="col">Status</th>
              <th scope="col">Raised</th>
            </tr>
          </thead>
          <tbody>
            {approvals.map((approval) => (
              <tr key={approval.id}>
                <th scope="row">
                  <Link href={`/app/${orgId}/approvals/${approval.id}`}>
                    {preview(approval.proposalBody)}
                  </Link>
                </th>
                <td>{approval.actionType}</td>
                <td>{approval.status}</td>
                <td>{new Date(approval.createdAt).toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

/** A short, plain-text excerpt. Never rendered as markup — this text came from a model. */
function preview(body: string | undefined): string {
  if (!body) {
    return "(no proposed text)";
  }
  const collapsed = body.replace(/\s+/g, " ").trim();
  return collapsed.length > 80 ? `${collapsed.slice(0, 77)}…` : collapsed;
}

function isApprovalStatus(value: unknown): value is ApprovalStatus {
  return typeof value === "string" && (STATUSES as string[]).includes(value);
}
