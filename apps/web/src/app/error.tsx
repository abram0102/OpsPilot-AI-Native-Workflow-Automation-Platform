"use client";

import { useEffect } from "react";

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
    <div role="alert" style={{ padding: "3rem 1.5rem", textAlign: "center" }}>
      <h1>Something went wrong</h1>
      <p style={{ color: "var(--muted)", marginTop: "0.5rem" }}>
        An unexpected error occurred. You can try again, or reload the page.
      </p>
      <button
        type="button"
        onClick={reset}
        style={{
          marginTop: "1.5rem",
          padding: "0.6rem 1.25rem",
          borderRadius: "8px",
          border: "1px solid var(--border)",
          background: "var(--surface)",
          color: "var(--foreground)",
          cursor: "pointer",
        }}
      >
        Try again
      </button>
    </div>
  );
}
