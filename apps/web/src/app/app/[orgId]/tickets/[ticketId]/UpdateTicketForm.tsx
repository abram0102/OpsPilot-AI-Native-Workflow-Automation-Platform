"use client";

import { useActionState } from "react";
import ui from "@/styles/ui.module.css";
import type { UpdateTicketState } from "./actions";
import type { TicketPriority, TicketStatus } from "@/lib/api/tickets";

const initialState: UpdateTicketState = {};

interface UpdateTicketFormProps {
  action: (prevState: UpdateTicketState, formData: FormData) => Promise<UpdateTicketState>;
  version: number;
  status: TicketStatus;
  priority: TicketPriority;
}

/** Status/priority editing "with collision errors" (docs/TASKS.md T12) — the backend's own
    stale-version/illegal-transition messages (ADR-029/ADR-032) are shown as-is. */
export function UpdateTicketForm({ action, version, status, priority }: UpdateTicketFormProps) {
  const [state, formAction, pending] = useActionState(action, initialState);

  return (
    <form action={formAction} className={ui.card}>
      <h2 className={ui.cardTitle}>Properties</h2>
      <input type="hidden" name="version" value={version} />

      <div className={ui.formGrid}>
        <div className={ui.field}>
          <label htmlFor="edit-status">Status</label>
          <select id="edit-status" name="status" defaultValue={status}>
            <option value="OPEN">Open</option>
            <option value="IN_PROGRESS">In progress</option>
            <option value="RESOLVED">Resolved</option>
            <option value="CLOSED">Closed</option>
          </select>
        </div>

        <div className={ui.field}>
          <label htmlFor="edit-priority">Priority</label>
          <select id="edit-priority" name="priority" defaultValue={priority}>
            <option value="LOW">Low</option>
            <option value="MEDIUM">Medium</option>
            <option value="HIGH">High</option>
          </select>
        </div>
      </div>

      {state.error && (
        <div role="alert" className={ui.alertError}>
          <span>{state.error}</span>
          <button type="button" className={ui.secondary} onClick={() => window.location.reload()}>
            Reload
          </button>
        </div>
      )}

      <div className={ui.actionsEnd}>
        <button type="submit" disabled={pending}>
          {pending ? "Saving…" : "Save"}
        </button>
      </div>
    </form>
  );
}
