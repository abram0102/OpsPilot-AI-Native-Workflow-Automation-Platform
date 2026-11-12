import { redirect } from "next/navigation";
import Link from "next/link";
import { fetchMe } from "@/lib/api/me";
import { getApproval } from "@/lib/api/approvals";
import { StatusBadge } from "@/components/ui/StatusBadge";
import ui from "@/styles/ui.module.css";
import { decideApprovalAction } from "./actions";
import { DecisionControls } from "./DecisionControls";
import styles from "./page.module.css";

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
      <main id="main-content" tabIndex={-1} className={ui.pageNarrow}>
        <h1>No access</h1>
        <p className={ui.lead}>You are not a member of this organization.</p>
      </main>
    );
  }
  if (membership.role !== "ADMIN" && membership.role !== "OWNER") {
    return (
      <div className={ui.pageNarrow}>
        <h1>Approval</h1>
        <p className={ui.alertNotice}>Only owners and admins can review proposed replies.</p>
      </div>
    );
  }

  const approval = await getApproval(orgId, approvalId);
  const pending = approval.status === "PENDING";

  return (
    <div className={styles.page}>
      <Link className={ui.backLink} href={`/app/${orgId}/approvals`}>
        ← Back to approvals
      </Link>

      <div className={ui.pageHeaderText}>
        <h1>Proposed {approval.actionType === "PUBLISH_INTERNAL_REPLY" ? "reply" : "action"}</h1>
        <div className={ui.actions}>
          <StatusBadge value={approval.status} />
          <span className={ui.badge}>{approval.actionType.replaceAll("_", " ")}</span>
        </div>
      </div>

      <section className={ui.card}>
        <dl className={ui.metaList}>
          <div className={ui.metaItem}>
            <dt>Ticket</dt>
            <dd>
              <Link className={ui.mono} href={`/app/${orgId}/tickets/${approval.ticketId}`}>
                {approval.ticketId}
              </Link>
            </dd>
          </div>
          <div className={ui.metaItem}>
            <dt>Agent run</dt>
            <dd className={ui.mono}>{approval.runId}</dd>
          </div>
          <div className={ui.metaItem}>
            <dt>Raised</dt>
            <dd>{new Date(approval.createdAt).toLocaleString()}</dd>
          </div>
          {approval.decisionAt && (
            <div className={ui.metaItem}>
              <dt>Decided</dt>
              <dd>{new Date(approval.decisionAt).toLocaleString()}</dd>
            </div>
          )}
        </dl>
      </section>

      <section className={ui.card}>
        <div className={ui.sectionTitle}>
          <h2 className={ui.cardTitle}>Exact text to be published</h2>
        </div>
        {/* Plain text, deliberately. This came from a model reading untrusted input. */}
        <blockquote className={styles.proposal}>
          {approval.proposalBody ?? "(this proposal carries no text)"}
        </blockquote>
        <p className={ui.hint}>
          This is the text that will be posted, character for character. It cannot be edited here —
          approving publishes exactly what is shown above.
        </p>
      </section>

      <section className={ui.card}>
        <div className={ui.sectionTitle}>
          <h2 className={ui.cardTitle}>Evidence</h2>
          {approval.evidence.length > 0 && (
            <span className={ui.badge}>{approval.evidence.length}</span>
          )}
        </div>
        {approval.evidence.length === 0 ? (
          <p role="note" className={ui.alertNotice}>
            No sources were cited. Treat the draft with extra care: nothing above is backed by a
            knowledge document.
          </p>
        ) : (
          <ul className={styles.evidenceList}>
            {approval.evidence.map((item) => (
              <li key={`${item.documentId}-${item.chunkIndex}`} className={styles.evidenceItem}>
                <span className={styles.evidenceTitle}>{item.title ?? "Untitled document"}</span>
                {item.chunkIndex !== undefined && (
                  <span className={ui.hint}>excerpt {item.chunkIndex}</span>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>

      {pending ? (
        <DecisionControls
          approve={decideApprovalAction.bind(null, orgId, approvalId, "APPROVED")}
          reject={decideApprovalAction.bind(null, orgId, approvalId, "REJECTED")}
        />
      ) : (
        <section className={ui.card}>
          <h2 className={ui.cardTitle}>Decision</h2>
          {/* Terminal and immutable: there is no control here to change it. */}
          <p role="status" className={ui.alert}>
            This approval is <strong>{approval.status}</strong> and cannot be changed.
          </p>
        </section>
      )}
    </div>
  );
}
