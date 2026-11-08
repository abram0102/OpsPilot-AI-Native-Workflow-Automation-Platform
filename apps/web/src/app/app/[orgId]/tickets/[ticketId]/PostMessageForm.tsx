"use client";

import { useActionState } from "react";
import type { PostMessageState } from "./actions";

const initialState: PostMessageState = {};

interface PostMessageFormProps {
  action: (prevState: PostMessageState, formData: FormData) => Promise<PostMessageState>;
}

export function PostMessageForm({ action }: PostMessageFormProps) {
  const [state, formAction, pending] = useActionState(action, initialState);

  return (
    <form action={formAction}>
      <label htmlFor="message-body">Reply</label>
      <br />
      <textarea id="message-body" name="body" required />
      <br />
      {state.error && <p role="alert">{state.error}</p>}
      <button type="submit" disabled={pending}>
        {pending ? "Sending…" : "Send"}
      </button>
    </form>
  );
}
