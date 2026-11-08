"use client";

import { useActionState } from "react";
import { createOrganizationAction, type CreateOrganizationState } from "./actions";

const initialState: CreateOrganizationState = {};

/** The create-organization form T08 left as "not implemented here yet" (docs/TASKS.md T09). */
export function CreateOrganizationForm() {
  const [state, formAction, pending] = useActionState(createOrganizationAction, initialState);

  return (
    <form action={formAction}>
      <div>
        <label htmlFor="org-name">Organization name</label>
        <br />
        <input id="org-name" name="name" required maxLength={200} />
      </div>
      <div>
        <label htmlFor="org-slug">URL slug</label>
        <br />
        <input
          id="org-slug"
          name="slug"
          required
          maxLength={63}
          pattern="[a-z0-9]+(-[a-z0-9]+)*"
          aria-describedby="org-slug-hint"
        />
        <p id="org-slug-hint">
          Lowercase letters, numbers and hyphens only — this becomes part of the org&apos;s URL.
        </p>
      </div>
      {state.error && <p role="alert">{state.error}</p>}
      <button type="submit" disabled={pending}>
        {pending ? "Creating…" : "Create organization"}
      </button>
    </form>
  );
}
