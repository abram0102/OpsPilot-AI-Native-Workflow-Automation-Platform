"use client";

import { useActionState } from "react";
import type { InviteMemberState } from "./actions";

const initialState: InviteMemberState = {};

interface InviteMemberFormProps {
  action: (prevState: InviteMemberState, formData: FormData) => Promise<InviteMemberState>;
  canGrantOwner: boolean;
}

/** Owner/admin-only invite form. Shows the one-time invitation link exactly once (docs/TASKS.md T09). */
export function InviteMemberForm({ action, canGrantOwner }: InviteMemberFormProps) {
  const [state, formAction, pending] = useActionState(action, initialState);

  return (
    <section>
      <h2>Invite a member</h2>
      <form action={formAction}>
        <label htmlFor="invite-email">Email</label>
        <br />
        <input id="invite-email" name="email" type="email" required />
        <br />
        <label htmlFor="invite-role">Role</label>
        <br />
        <select id="invite-role" name="role" defaultValue="MEMBER">
          <option value="VIEWER">Viewer</option>
          <option value="MEMBER">Member</option>
          <option value="ADMIN">Admin</option>
          {canGrantOwner && <option value="OWNER">Owner</option>}
        </select>
        <br />
        <button type="submit" disabled={pending}>
          {pending ? "Sending…" : "Create invitation"}
        </button>
      </form>
      {state.error && <p role="alert">{state.error}</p>}
      {state.token && (
        <div role="status">
          <p>
            Invitation created for {state.invitedEmail}. There is no email delivery yet
            (docs/TASKS.md T09) — share this link with them yourself; it works once and expires
            per <code>API_INVITATION_TTL</code>:
          </p>
          <p>
            <a href={`/invitations/accept?token=${encodeURIComponent(state.token)}`}>
              {`/invitations/accept?token=${state.token}`}
            </a>
          </p>
        </div>
      )}
    </section>
  );
}
