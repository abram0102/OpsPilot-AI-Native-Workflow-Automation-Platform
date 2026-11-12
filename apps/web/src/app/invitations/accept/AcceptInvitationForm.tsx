"use client";

import { useActionState } from "react";
import ui from "@/styles/ui.module.css";
import { acceptInvitationAction, type AcceptInvitationState } from "./actions";

const initialState: AcceptInvitationState = {};

export function AcceptInvitationForm({ defaultToken }: { defaultToken: string }) {
  const [state, formAction, pending] = useActionState(acceptInvitationAction, initialState);

  return (
    <form action={formAction} className={ui.card}>
      <div className={ui.field}>
        <label htmlFor="invitation-token">Invitation token</label>
        <input
          id="invitation-token"
          name="token"
          className={ui.mono}
          defaultValue={defaultToken}
          required
        />
      </div>

      {state.error && (
        <p role="alert" className={ui.alertError}>
          {state.error}
        </p>
      )}

      <div className={ui.actionsEnd}>
        <button type="submit" disabled={pending}>
          {pending ? "Joining…" : "Accept invitation"}
        </button>
      </div>
    </form>
  );
}
