"use client";

import { useEffect } from "react";
import ui from "@/styles/ui.module.css";
import styles from "./status.module.css";

/**
 * Root error boundary. Next.js requires this to be a Client Component.
 * Never surfaces `error.message` as the headline — that can leak internal
 * detail — only to the console, for local debugging.
 */
export default function GlobalError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error(error);
  }, [error]);

  return (
    <div role="alert" className={styles.centered}>
      <h1>Something went wrong</h1>
      <p className={ui.lead}>
        An unexpected error occurred. You can try again, or reload the page.
      </p>
      <button type="button" className={ui.secondary} onClick={reset}>
        Try again
      </button>
    </div>
  );
}
