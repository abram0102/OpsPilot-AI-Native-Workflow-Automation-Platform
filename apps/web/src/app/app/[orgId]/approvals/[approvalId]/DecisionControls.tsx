"use client";

import { useActionState } from "react";
import type { DecisionState } from "./actions";

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
    <div>
      <h2 id="decision-heading">Decision</h2>
      <p id="decision-help">
        Approving publishes this exact text. Rejecting records the decision and the agent publishes
        nothing.
      </p>

      <div>
        <form action={approveAction} style={{ display: "inline" }}>
          <button type="submit" disabled={busy} aria-describedby="decision-help">
            {approving ? "Approving…" : "Approve and publish"}
          </button>
        </form>{" "}
        <form action={rejectAction} style={{ display: "inline" }}>
          <button type="submit" disabled={busy} aria-describedby="decision-help">
            {rejecting ? "Rejecting…" : "Reject"}
          </button>
        </form>
      </div>

      {/* role="alert" so a decision or a conflict is announced, not just rendered. */}
      {state.error && (
        <p role="alert">
          {state.error}
          {state.conflict && " Another reviewer may have decided it first."}
        </p>
      )}
      {state.decided && <p role="status">Recorded: {state.decided}.</p>}
    </div>
  );
}
