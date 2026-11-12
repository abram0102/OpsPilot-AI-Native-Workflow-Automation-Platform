"use client";

import type { ReactNode } from "react";
import ui from "@/styles/ui.module.css";

interface FilterFormProps {
  /** Accessible name for the group of filters, e.g. "Filter tickets". */
  label: string;
  children: ReactNode;
}

/**
 * A `GET` filter bar that submits as soon as a control changes, so there is no
 * "Apply" button to press.
 *
 * <p>The `<noscript>` button is not decoration. These filters are real
 * server-side query parameters, and the form is plain `method="get"` — without
 * scripting the only thing missing is something to submit it, so the button
 * reappears exactly in that case. Removing it unconditionally would have made
 * the one non-JS-dependent interaction in the product silently do nothing.
 *
 * <p>`onChange` on the form rather than per control: change events from the
 * selects bubble, so one handler covers however many filters a page adds.
 */
export function FilterForm({ label, children }: FilterFormProps) {
  return (
    <form
      method="get"
      aria-label={label}
      className={ui.toolbar}
      onChange={(event) => event.currentTarget.requestSubmit()}
    >
      {children}
      <noscript>
        <button type="submit" className={ui.secondary}>
          Apply filters
        </button>
      </noscript>
    </form>
  );
}
