import { redirect } from "next/navigation";
import Link from "next/link";
import { fetchMe } from "@/lib/api/me";
import { getApproval } from "@/lib/api/approvals";
import { decideApprovalAction } from "./actions";
import { DecisionControls } from "./DecisionControls";

export const instant = false;

/**
 * One approval: the exact proposal, its evidence, and the decision (docs/TASKS.md T22).
 *
 * <p>The proposed text is rendered as plain text inside a `<blockquote>`, never as markup. It was
 * written by a model from untrusted ticket and document content, and this page is read by the
 * person with the authority to publish it — which is exactly the combination that makes rendering
 * it as HTML a bad idea (AGENTS.md rule 12).
 */
export default async function ApprovalDetailPage({
  params,
}: PageProps<"/app/[orgId]/approvals/[approvalId]">) {
  const { orgId, approvalId } = await params;

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
  if (membership.role !== "ADMIN" && membership.role !== "OWNER") {
    return (
      <div>
        <h1>Approval</h1>
        <p>Only owners and admins can review proposed replies.</p>
      </div>
    );
  }

  const approval = await getApproval(orgId, approvalId);
  const pending = approval.status === "PENDING";

  return (
    <div>
      <p>
        <Link href={`/app/${orgId}/approvals`}>← Back to approvals</Link>
      </p>

      <h1>Proposed {approval.actionType === "PUBLISH_INTERNAL_REPLY" ? "reply" : "action"}</h1>

      <dl>
        <dt>Status</dt>
        <dd>{approval.status}</dd>
        <dt>Ticket</dt>
        <dd>
          <Link href={`/app/${orgId}/tickets/${approval.ticketId}`}>{approval.ticketId}</Link>
        </dd>
        <dt>Agent run</dt>
        <dd>{approval.runId}</dd>
        <dt>Raised</dt>
        <dd>{new Date(approval.createdAt).toLocaleString()}</dd>
        {approval.decisionAt && (
          <>
            <dt>Decided</dt>
            <dd>{new Date(approval.decisionAt).toLocaleString()}</dd>
          </>
        )}
      </dl>

      <h2>Exact text to be published</h2>
      {/* Plain text, deliberately. This came from a model reading untrusted input. */}
      <blockquote style={{ whiteSpace: "pre-wrap" }}>
        {approval.proposalBody ?? "(this proposal carries no text)"}
      </blockquote>
      <p>
        This is the text that will be posted, character for character. It cannot be edited here —
        approving publishes exactly what is shown above.
      </p>

      <h2>Evidence</h2>
      {approval.evidence.length === 0 ? (
        <p>
          No sources were cited. Treat the draft with extra care: nothing above is backed by a
          knowledge document.
        </p>
      ) : (
        <ul>
          {approval.evidence.map((item) => (
            <li key={`${item.documentId}-${item.chunkIndex}`}>
              {item.title ?? "Untitled document"}
              {item.chunkIndex !== undefined && ` (excerpt ${item.chunkIndex})`}
            </li>
          ))}
        </ul>
      )}

      {pending ? (
        <DecisionControls
          approve={decideApprovalAction.bind(null, orgId, approvalId, "APPROVED")}
          reject={decideApprovalAction.bind(null, orgId, approvalId, "REJECTED")}
        />
      ) : (
        <>
          <h2>Decision</h2>
          {/* Terminal and immutable: there is no control here to change it. */}
          <p role="status">
            This approval is <strong>{approval.status}</strong> and cannot be changed.
          </p>
        </>
      )}
    </div>
  );
}
