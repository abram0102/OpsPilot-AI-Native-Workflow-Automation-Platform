"use client";

import { useActionState } from "react";
import { acceptInvitationAction, type AcceptInvitationState } from "./actions";

const initialState: AcceptInvitationState = {};

export function AcceptInvitationForm({ defaultToken }: { defaultToken: string }) {
  const [state, formAction, pending] = useActionState(acceptInvitationAction, initialState);

  return (
    <form action={formAction}>
      <label htmlFor="invitation-token">Invitation token</label>
      <br />
      <input id="invitation-token" name="token" defaultValue={defaultToken} required />
      {state.error && <p role="alert">{state.error}</p>}
      <br />
      <button type="submit" disabled={pending}>
        {pending ? "Joining…" : "Accept invitation"}
      </button>
    </form>
  );
}
