"use client";

import { useActionState, useRef } from "react";
import type { CreateTicketState } from "./actions";

const initialState: CreateTicketState = {};

interface CreateTicketDialogProps {
  action: (prevState: CreateTicketState, formData: FormData) => Promise<CreateTicketState>;
}

/** The create-ticket dialog (docs/TASKS.md T12). A native `<dialog>` — built-in focus trapping
    and Escape-to-close, no extra dependency needed. */
export function CreateTicketDialog({ action }: CreateTicketDialogProps) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const [state, formAction, pending] = useActionState(action, initialState);

  return (
    <>
      <button type="button" onClick={() => dialogRef.current?.showModal()}>
        Create ticket
      </button>
      <dialog ref={dialogRef} aria-labelledby="create-ticket-heading">
        <h2 id="create-ticket-heading">Create ticket</h2>
        <form action={formAction}>
          <label htmlFor="ticket-customer-name">Customer name</label>
          <br />
          <input id="ticket-customer-name" name="customerName" required />
          <br />
          <label htmlFor="ticket-subject">Subject</label>
          <br />
          <input id="ticket-subject" name="subject" required />
          <br />
          <label htmlFor="ticket-description">Description</label>
          <br />
          <textarea id="ticket-description" name="description" required />
          <br />
          <label htmlFor="ticket-priority">Priority</label>
          <br />
          <select id="ticket-priority" name="priority" defaultValue="MEDIUM">
            <option value="LOW">Low</option>
            <option value="MEDIUM">Medium</option>
            <option value="HIGH">High</option>
          </select>
          <br />
          {state.error && <p role="alert">{state.error}</p>}
          <button type="button" onClick={() => dialogRef.current?.close()}>
            Cancel
          </button>
          <button type="submit" disabled={pending}>
            {pending ? "Creating…" : "Create"}
          </button>
        </form>
      </dialog>
    </>
  );
}
