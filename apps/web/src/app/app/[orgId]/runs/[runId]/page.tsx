import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { fetchMe } from "@/lib/api/me";
import { ApiError, getRun, isTerminalRunStatus, listRunSteps } from "@/lib/api/runs";
import { listApprovals, type Approval } from "@/lib/api/approvals";
import { StatusBadge } from "@/components/ui/StatusBadge";
import ui from "@/styles/ui.module.css";
import { cancelRunAction } from "./actions";
import { CancelRunControl } from "./CancelRunControl";
import { RunPoller } from "./RunPoller";
import styles from "./page.module.css";

export const instant = false;

/**
 * One run: status, trace, usage, and whatever it is waiting on (docs/TASKS.md T27).
 *
 * <p>Two things this page deliberately does not do. It renders every trace field as plain text —
 * the sanitized columns were written by a model reading untrusted ticket and document content, so
 * rendering them as markup would be the injection surface docs/SECURITY_AND_TESTING.md warns
 * about. And it shows no cost figure: the API exposes token counts a provider reported and no
 * price, because a number labelled as spend would read as billed (ADR-041).
 */
export default async function RunDetailPage({ params }: PageProps<"/app/[orgId]/runs/[runId]">) {
  const { orgId, runId } = await params;

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

  const canSeeApprovals = membership.role === "ADMIN" || membership.role === "OWNER";
  const canCancel = membership.role !== "VIEWER";

  // A run id that does not exist in this tenant answers 404, and the API deliberately does not
  // distinguish "no such run" from "another tenant's run" (AGENTS.md invariants). Rendering that as
  // "Something went wrong" would be wrong twice over: it is not an unexpected failure, and the
  // reader's next step is to go back to the list, not to retry.
  let run;
  let steps;
  try {
    [run, steps] = await Promise.all([getRun(orgId, runId), listRunSteps(orgId, runId)]);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      notFound();
    }
    throw error;
  }

  // Only owners and admins may list approvals at all (the API answers 403 otherwise), so this is
  // not requested for anyone else rather than requested and swallowed.
  const approvals: Approval[] = canSeeApprovals ? await listApprovals(orgId, undefined, runId) : [];

  const terminal = isTerminalRunStatus(run.status);

  return (
    <div className={styles.page}>
      <Link className={ui.backLink} href={`/app/${orgId}/runs`}>
        ← Back to runs
      </Link>

      <div className={ui.pageHeader}>
        <div className={ui.pageHeaderText}>
          <h1>
            Run <span className={ui.mono}>{run.id.slice(0, 8)}</span>
          </h1>
          <div className={ui.actions}>
            <StatusBadge value={run.status} />
            <span className={ui.badge}>{run.workflow.replaceAll("_", " ")}</span>
            {run.lastErrorCode && <span className={styles.errorCode}>{run.lastErrorCode}</span>}
          </div>
          <RunPoller orgId={orgId} runId={run.id} status={run.status} />
        </div>
        {canCancel && !terminal && (
          <CancelRunControl action={cancelRunAction.bind(null, orgId, run.id)} />
        )}
      </div>

      <section className={ui.card}>
        <dl className={ui.metaList}>
          <div className={ui.metaItem}>
            <dt>Ticket</dt>
            <dd>
              <Link className={ui.mono} href={`/app/${orgId}/tickets/${run.ticketId}`}>
                {run.ticketId.slice(0, 8)}
              </Link>
            </dd>
          </div>
          <div className={ui.metaItem}>
            <dt>Steps used</dt>
            <dd>{run.stepsUsed}</dd>
          </div>
          <div className={ui.metaItem}>
            <dt>Attempts</dt>
            <dd>{run.attemptCount}</dd>
          </div>
          <div className={ui.metaItem}>
            <dt>Tokens in / out</dt>
            {/* Reported by the provider, not estimated here. A run that never reached a model
                shows zeros, which is the honest reading of "nothing was spent yet". */}
            <dd>
              {run.totalInputTokens} / {run.totalOutputTokens}
            </dd>
          </div>
          <div className={ui.metaItem}>
            <dt>Started</dt>
            <dd>{new Date(run.createdAt).toLocaleString()}</dd>
          </div>
          <div className={ui.metaItem}>
            <dt>Last update</dt>
            <dd>{new Date(run.updatedAt).toLocaleString()}</dd>
          </div>
        </dl>
      </section>

      {canSeeApprovals && approvals.length > 0 && (
        <section className={ui.card}>
          <div className={ui.sectionTitle}>
            <h2 className={ui.cardTitle}>Approvals</h2>
            <span className={ui.badge}>{approvals.length}</span>
          </div>
          <ul className={styles.approvalList}>
            {approvals.map((approval) => (
              <li key={approval.id} className={styles.approvalItem}>
                <div className={styles.approvalHead}>
                  <StatusBadge value={approval.status} />
                  <span className={ui.badge}>{approval.actionType.replaceAll("_", " ")}</span>
                  <Link
                    className={styles.approvalLink}
                    href={`/app/${orgId}/approvals/${approval.id}`}
                  >
                    {approval.status === "PENDING" ? "Review and decide" : "View decision"} →
                  </Link>
                </div>
                {/* Plain text. A model wrote it. */}
                <blockquote className={styles.proposal}>
                  {approval.proposalBody ?? "(this proposal carries no text)"}
                </blockquote>
                <p className={ui.hint}>
                  {approval.evidence.length === 0
                    ? "No sources were cited."
                    : `Cites ${approval.evidence.length} source${
                        approval.evidence.length === 1 ? "" : "s"
                      }: ${approval.evidence
                        .map((item) => item.title ?? "Untitled document")
                        .join(", ")}`}
                </p>
              </li>
            ))}
          </ul>
        </section>
      )}

      {run.status === "AWAITING_APPROVAL" && !canSeeApprovals && (
        <p className={ui.alertNotice}>
          This run is waiting for a human decision. Only owners and admins can review what it
          proposed.
        </p>
      )}

      <section className={ui.card}>
        <div className={ui.sectionTitle}>
          <h2 className={ui.cardTitle}>Trace</h2>
          {steps.items.length > 0 && <span className={ui.badge}>{steps.items.length}</span>}
        </div>

        {steps.items.length === 0 ? (
          <p className={ui.lead}>
            {terminal
              ? "This run recorded no steps."
              : "No steps recorded yet. The worker writes each step as it completes it."}
          </p>
        ) : (
          <ol className={styles.trace}>
            {steps.items.map((step) => (
              <li key={step.id} className={styles.step}>
                <div className={styles.stepHead}>
                  <span className={styles.sequence}>{step.sequence}</span>
                  <span className={styles.stepType}>{step.stepType.replaceAll("_", " ")}</span>
                  <StatusBadge value={step.status} />
                  {step.errorCode && <span className={styles.errorCode}>{step.errorCode}</span>}
                </div>

                <dl className={styles.stepMeta}>
                  {step.model && (
                    <div className={ui.metaItem}>
                      <dt>Model</dt>
                      <dd className={ui.mono}>{step.model}</dd>
                    </div>
                  )}
                  {step.durationMs !== undefined && (
                    <div className={ui.metaItem}>
                      <dt>Duration</dt>
                      <dd>{step.durationMs} ms</dd>
                    </div>
                  )}
                  {(step.tokensIn !== undefined || step.tokensOut !== undefined) && (
                    <div className={ui.metaItem}>
                      <dt>Tokens in / out</dt>
                      <dd>
                        {step.tokensIn ?? "—"} / {step.tokensOut ?? "—"}
                      </dd>
                    </div>
                  )}
                </dl>

                {step.sanitizedInput && (
                  <details className={styles.payload}>
                    <summary>Input</summary>
                    <pre className={styles.pre}>{step.sanitizedInput}</pre>
                  </details>
                )}
                {step.sanitizedOutput && (
                  <details className={styles.payload}>
                    <summary>Output</summary>
                    <pre className={styles.pre}>{step.sanitizedOutput}</pre>
                  </details>
                )}
              </li>
            ))}
          </ol>
        )}

        {steps.nextCursor && (
          <p className={ui.hint}>
            This run has more steps than one page shows. Paging through a trace is not wired up yet
            (docs/TASKS.md T27).
          </p>
        )}
      </section>
    </div>
  );
}
