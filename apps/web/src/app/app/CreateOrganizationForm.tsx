"use client";

import { useActionState } from "react";
import ui from "@/styles/ui.module.css";
import { createOrganizationAction, type CreateOrganizationState } from "./actions";

const initialState: CreateOrganizationState = {};

/** The create-organization form T08 left as "not implemented here yet" (docs/TASKS.md T09). */
export function CreateOrganizationForm() {
  const [state, formAction, pending] = useActionState(createOrganizationAction, initialState);

  return (
    <form action={formAction} className={ui.card}>
      <h2 className={ui.cardTitle}>Create an organization</h2>

      <div className={ui.formGrid}>
        <div className={ui.field}>
          <label htmlFor="org-name">Organization name</label>
          <input id="org-name" name="name" required maxLength={200} />
        </div>

        <div className={ui.field}>
          <label htmlFor="org-slug">URL slug</label>
          <input
            id="org-slug"
            name="slug"
            required
            maxLength={63}
            pattern="[a-z0-9]+(-[a-z0-9]+)*"
            aria-describedby="org-slug-hint"
          />
          <p id="org-slug-hint" className={ui.hint}>
            Lowercase letters, numbers and hyphens only — this becomes part of the org&apos;s URL.
          </p>
        </div>
      </div>

      {state.error && (
        <p role="alert" className={ui.alertError}>
          {state.error}
        </p>
      )}

      <div className={ui.actions}>
        <button type="submit" disabled={pending}>
          {pending ? "Creating…" : "Create organization"}
        </button>
      </div>
    </form>
  );
}
