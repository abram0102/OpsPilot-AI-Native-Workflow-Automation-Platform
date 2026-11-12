"use client";

import { useActionState } from "react";
import ui from "@/styles/ui.module.css";
import type { CancelRunState } from "./actions";

const initialState: CancelRunState = {};

interface CancelRunControlProps {
  action: (prevState: CancelRunState, formData: FormData) => Promise<CancelRunState>;
}

/**
 * Cancels a run that has not finished.
 *
 * <p>`disabled={pending}` is the duplicate-click guard T27's acceptance criteria ask for: the
 * second click of a double-click never reaches the server, and the first one's result — including
 * "it already finished" — is what the page reports. Cancelling is also idempotent server-side, so
 * a click that does get through twice cannot cancel anything twice.
 */
export function CancelRunControl({ action }: CancelRunControlProps) {
  const [state, formAction, pending] = useActionState(action, initialState);

  if (state.cancelled) {
    return (
      <p role="status" className={ui.alertSuccess}>
        Cancellation recorded.
      </p>
    );
  }

  return (
    <div className={ui.pageHeaderText}>
      <form action={formAction}>
        <button type="submit" className={ui.danger} disabled={pending}>
          {pending ? "Cancelling…" : "Cancel run"}
        </button>
      </form>
      {state.error && (
        <p role="alert" className={state.conflict ? ui.alertNotice : ui.alertError}>
          {state.error}
        </p>
      )}
    </div>
  );
}
