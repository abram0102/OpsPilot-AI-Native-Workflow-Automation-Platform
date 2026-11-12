import { act, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { RunPoller } from "./RunPoller";

const refresh = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ refresh }),
}));

/**
 * Advances the interval and lets its awaited fetch/json settle.
 *
 * <p>Inside `act` so React commits the resulting state update, and using the timer API rather than
 * `findBy*`/`waitFor` — those poll on real timers, which never advance here, so they would simply
 * hang until the test timed out (they did, before this helper existed).
 */
async function tick(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
    for (let i = 0; i < 5; i++) {
      await Promise.resolve();
    }
  });
}

describe("RunPoller", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    refresh.mockClear();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it("does not poll a run that has already finished", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch");

    const { container } = render(
      <RunPoller orgId="org" runId="run" status="COMPLETED" intervalMs={1000} />,
    );

    await tick(5000);

    // A terminal run can only ever return the same row, so asking again is pure waste.
    expect(fetchMock).not.toHaveBeenCalled();
    expect(container).toBeEmptyDOMElement();
  });

  it("refreshes the page only once the status actually changes", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(new Response(JSON.stringify({ status: "RUNNING" }), { status: 200 }));

    render(<RunPoller orgId="org" runId="run" status="RUNNING" intervalMs={1000} />);

    await tick(1000);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    // Same status came back: re-fetching the whole trace would be pointless.
    expect(refresh).not.toHaveBeenCalled();

    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({ status: "AWAITING_APPROVAL" }), { status: 200 }),
    );
    await tick(1000);

    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it("says live updates stopped instead of retrying a failing endpoint forever", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(new Response(JSON.stringify({ error: {} }), { status: 403 }));

    render(<RunPoller orgId="org" runId="run" status="RUNNING" intervalMs={1000} />);

    await tick(1000);

    expect(screen.getByRole("status")).toHaveTextContent("Live updates stopped");

    // The interval is cleared, so a page the caller may never read is not polled in a loop.
    await tick(5000);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("aborts a request still in flight when it unmounts", async () => {
    let capturedSignal: AbortSignal | undefined;
    vi.spyOn(globalThis, "fetch").mockImplementation((_input, init) => {
      capturedSignal = init?.signal ?? undefined;
      return new Promise(() => {}); // never settles: the request is still in flight
    });

    const { unmount } = render(
      <RunPoller orgId="org" runId="run" status="RUNNING" intervalMs={1000} />,
    );

    await tick(1000);
    expect(capturedSignal?.aborted).toBe(false);

    unmount();

    // Without this, a late response could call router.refresh() on a page that is gone.
    expect(capturedSignal?.aborted).toBe(true);
  });
});
