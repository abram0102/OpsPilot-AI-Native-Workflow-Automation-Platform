"use client";

import { useActionState } from "react";
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
    <form action={formAction}>
      <input type="hidden" name="version" value={version} />
      <label htmlFor="edit-status">Status</label>
      <br />
      <select id="edit-status" name="status" defaultValue={status}>
        <option value="OPEN">Open</option>
        <option value="IN_PROGRESS">In progress</option>
        <option value="RESOLVED">Resolved</option>
        <option value="CLOSED">Closed</option>
      </select>
      <br />
      <label htmlFor="edit-priority">Priority</label>
      <br />
      <select id="edit-priority" name="priority" defaultValue={priority}>
        <option value="LOW">Low</option>
        <option value="MEDIUM">Medium</option>
        <option value="HIGH">High</option>
      </select>
      <br />
      {state.error && (
        <p role="alert">
          {state.error}{" "}
          <button type="button" onClick={() => window.location.reload()}>
            Reload
          </button>
        </p>
      )}
      <button type="submit" disabled={pending}>
        {pending ? "Saving…" : "Save"}
      </button>
    </form>
  );
}
