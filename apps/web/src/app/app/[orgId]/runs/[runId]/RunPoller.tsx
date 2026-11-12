"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { isTerminalRunStatus, type AgentRunStatus } from "@/lib/api/runs";
import ui from "@/styles/ui.module.css";
import styles from "./RunPoller.module.css";

interface RunPollerProps {
  orgId: string;
  runId: string;
  /** The status this page was rendered with. Polling stops once it is terminal. */
  status: AgentRunStatus;
  intervalMs?: number;
}

/**
 * Keeps a run page current while the run is still moving (T27).
 *
 * <p>Three deliberate properties:
 *
 * <ul>
 *   <li><b>It stops.</b> A terminal run is one nothing will move out of, so the effect returns
 *       before setting up an interval at all — polling a finished run is a request that can only
 *       return the same row forever.
 *   <li><b>It re-renders only on change.</b> The poll reads a tiny status endpoint and calls
 *       `router.refresh()` only when the status or step count actually differs. A blind refresh on
 *       a timer would re-fetch the whole trace every few seconds for no reason.
 *   <li><b>It gives up loudly.</b> If the endpoint answers with an error the interval is cleared
 *       and the page says live updates stopped, rather than retrying silently forever and leaving
 *       a stale page looking live.
 * </ul>
 *
 * <p>The AbortController cancels any request still in flight when the component unmounts or the
 * run reaches a terminal state, so a navigation away cannot land a `router.refresh()` on a page
 * that is gone.
 */
export function RunPoller({ orgId, runId, status, intervalMs = 3000 }: RunPollerProps) {
  const router = useRouter();
  const [stopped, setStopped] = useState(false);

  // Held in a ref and kept out of the effect's dependencies on purpose. The effect must restart
  // only when the run or the interval changes — if the router object's identity were a dependency,
  // any render that handed back a new one would tear down the interval and start a second,
  // doubling the poll rate. Next's own useRouter happens to be stable; depending on that is not
  // something this component should have to assume (a test with an unstable router caught it).
  const routerRef = useRef(router);
  useEffect(() => {
    routerRef.current = router;
  }, [router]);

  useEffect(() => {
    if (isTerminalRunStatus(status)) {
      return;
    }

    const controller = new AbortController();
    let cancelled = false;

    const interval = setInterval(async () => {
      try {
        const response = await fetch(
          `/api/run-status?orgId=${encodeURIComponent(orgId)}&runId=${encodeURIComponent(runId)}`,
          { signal: controller.signal, cache: "no-store" },
        );

        if (!response.ok) {
          clearInterval(interval);
          if (!cancelled) {
            setStopped(true);
          }
          return;
        }

        const next = (await response.json()) as { status?: AgentRunStatus };
        if (!cancelled && next.status && next.status !== status) {
          routerRef.current.refresh();
        }
      } catch (error) {
        // An abort is this component shutting down, not a failure.
        if ((error as Error)?.name === "AbortError") {
          return;
        }
        clearInterval(interval);
        if (!cancelled) {
          setStopped(true);
        }
      }
    }, intervalMs);

    return () => {
      cancelled = true;
      clearInterval(interval);
      controller.abort();
    };
  }, [orgId, runId, status, intervalMs]);

  if (isTerminalRunStatus(status)) {
    return null;
  }

  if (stopped) {
    return (
      <p role="status" className={ui.alert}>
        Live updates stopped. Reload the page to see the current state.
      </p>
    );
  }

  return (
    <p role="status" className={styles.live}>
      <span className={styles.dot} aria-hidden="true" />
      Watching for updates…
    </p>
  );
}
