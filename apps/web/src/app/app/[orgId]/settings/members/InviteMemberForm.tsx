"use client";

import { useActionState } from "react";
import ui from "@/styles/ui.module.css";
import type { InviteMemberState } from "./actions";
import styles from "./InviteMemberForm.module.css";

const initialState: InviteMemberState = {};

interface InviteMemberFormProps {
  action: (prevState: InviteMemberState, formData: FormData) => Promise<InviteMemberState>;
  canGrantOwner: boolean;
}

/** Owner/admin-only invite form. Shows the one-time invitation link exactly once (docs/TASKS.md T09). */
export function InviteMemberForm({ action, canGrantOwner }: InviteMemberFormProps) {
  const [state, formAction, pending] = useActionState(action, initialState);

  return (
    <section className={ui.card}>
      <h2 className={ui.cardTitle}>Invite a member</h2>
      <form action={formAction} className={styles.inviteRow}>
        <div className={ui.field}>
          <label htmlFor="invite-email">Email</label>
          <input id="invite-email" name="email" type="email" required />
        </div>

        <div className={ui.fieldInline}>
          <label htmlFor="invite-role">Role</label>
          <select id="invite-role" name="role" defaultValue="MEMBER">
            <option value="VIEWER">Viewer</option>
            <option value="MEMBER">Member</option>
            <option value="ADMIN">Admin</option>
            {canGrantOwner && <option value="OWNER">Owner</option>}
          </select>
        </div>

        <button type="submit" disabled={pending}>
          {pending ? "Sending…" : "Create invitation"}
        </button>
      </form>

      {state.error && (
        <p role="alert" className={ui.alertError}>
          {state.error}
        </p>
      )}

      {state.token && (
        <div role="status" className={styles.tokenPanel}>
          <p>
            Invitation created for {state.invitedEmail}. There is no email delivery yet
            (docs/TASKS.md T09) — share this link with them yourself; it works once and expires per{" "}
            <code>API_INVITATION_TTL</code>:
          </p>
          <a
            className={styles.tokenLink}
            href={`/invitations/accept?token=${encodeURIComponent(state.token)}`}
          >
            {`/invitations/accept?token=${state.token}`}
          </a>
        </div>
      )}
    </section>
  );
}
