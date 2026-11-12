"use client";

import { useActionState } from "react";
import ui from "@/styles/ui.module.css";
import type { PostMessageState } from "./actions";

const initialState: PostMessageState = {};

interface PostMessageFormProps {
  action: (prevState: PostMessageState, formData: FormData) => Promise<PostMessageState>;
}

export function PostMessageForm({ action }: PostMessageFormProps) {
  const [state, formAction, pending] = useActionState(action, initialState);

  return (
    <form action={formAction} className={ui.formGrid}>
      <div className={ui.field}>
        <label htmlFor="message-body">Reply</label>
        <textarea id="message-body" name="body" required />
      </div>

      {state.error && (
        <p role="alert" className={ui.alertError}>
          {state.error}
        </p>
      )}

      <div className={ui.actionsEnd}>
        <button type="submit" disabled={pending}>
          {pending ? "Sending…" : "Send"}
        </button>
      </div>
    </form>
  );
}
