"use client";

import { useActionState } from "react";
import ui from "@/styles/ui.module.css";
import type { StartRunState } from "./actions";

const initialState: StartRunState = {};

interface StartRunFormProps {
  action: (prevState: StartRunState, formData: FormData) => Promise<StartRunState>;
  /** Generated once, server-side, when the page rendered. See {@code startRunAction}. */
  idempotencyKey: string;
}

/**
 * Starts an agent run for this ticket.
 *
 * <p>On success the action redirects to the run's page, so this component has no success state to
 * render — a run that has merely been accepted has nothing to show here, and saying "done" would
 * claim the agent had run when nothing has executed it yet.
 */
export function StartRunForm({ action, idempotencyKey }: StartRunFormProps) {
  const [state, formAction, pending] = useActionState(action, initialState);

  return (
    <div className={ui.pageHeaderText}>
      <form action={formAction}>
        <input type="hidden" name="idempotencyKey" value={idempotencyKey} />
        <button type="submit" disabled={pending}>
          {pending ? "Requesting…" : "Ask the agent"}
        </button>
      </form>
      {state.error && (
        <p role="alert" className={ui.alertError}>
          {state.error}
        </p>
      )}
    </div>
  );
}
