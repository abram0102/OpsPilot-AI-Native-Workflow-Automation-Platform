"use client";

import { useActionState } from "react";
import ui from "@/styles/ui.module.css";
import type { DecisionState } from "./actions";
import styles from "./DecisionControls.module.css";

const initialState: DecisionState = {};

interface DecisionControlsProps {
  approve: (prevState: DecisionState, formData: FormData) => Promise<DecisionState>;
  reject: (prevState: DecisionState, formData: FormData) => Promise<DecisionState>;
}

/**
 * Approve and reject, as two separate forms (docs/TASKS.md T22).
 *
 * <p>Two forms rather than one with a hidden outcome field: a hidden input is a value the browser
 * could be made to change, and the two decisions are not interchangeable. Each button is its own
 * explicit submission.
 *
 * <p>Neither carries the proposal text, because the backend takes no body on these endpoints.
 */
export function DecisionControls({ approve, reject }: DecisionControlsProps) {
  const [approveState, approveAction, approving] = useActionState(approve, initialState);
  const [rejectState, rejectAction, rejecting] = useActionState(reject, initialState);

  const state = approveState.error || approveState.decided ? approveState : rejectState;
  const busy = approving || rejecting;

  return (
    <section className={styles.panel} aria-labelledby="decision-heading">
      <h2 id="decision-heading" className={ui.cardTitle}>
        Decision
      </h2>
      <p id="decision-help" className={ui.lead}>
        Approving publishes this exact text. Rejecting records the decision and the agent publishes
        nothing.
      </p>

      <div className={ui.actions}>
        <form action={approveAction}>
          <button type="submit" disabled={busy} aria-describedby="decision-help">
            {approving ? "Approving…" : "Approve and publish"}
          </button>
        </form>
        <form action={rejectAction}>
          <button
            type="submit"
            className={ui.danger}
            disabled={busy}
            aria-describedby="decision-help"
          >
            {rejecting ? "Rejecting…" : "Reject"}
          </button>
        </form>
      </div>

      {/* role="alert" so a decision or a conflict is announced, not just rendered. */}
      {state.error && (
        <p role="alert" className={ui.alertError}>
          {state.error}
          {state.conflict && " Another reviewer may have decided it first."}
        </p>
      )}
      {state.decided && (
        <p role="status" className={ui.alertSuccess}>
          Recorded: {state.decided}.
        </p>
      )}
    </section>
  );
}
