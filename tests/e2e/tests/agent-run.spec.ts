import { test, expect } from "../src/fixtures.js";

/**
 * The product's whole point, end to end in a browser: ask the agent, wait for it to propose
 * something, approve it, and see the reply published on the ticket (docs/TASKS.md T29 acceptance;
 * docs/SECURITY_AND_TESTING.md §2 "UI create-ticket -> run -> approve -> result").
 *
 * <p>This is the only test in the repository that exercises the whole chain in one go: Next.js
 * server action → API → outbox → Redis stream → Python worker → LangGraph with the mock provider →
 * approval row → the human's decision → the internal action API → a published ticket message. Every
 * link has unit and integration coverage; none of that proves the links are joined.
 *
 * <p>It runs with `AI_MODE=mock`, so the model's output is deterministic and no provider is called
 * (AGENTS.md rule 16).
 */

/** The author label the conversation thread renders on an agent-written message. */
const AGENT_MESSAGE = { name: "Agent", exact: true } as const;

test.describe("ask the agent and approve what it proposes", () => {
  test("a drafted reply is published only after a human approves it", async ({
    acmePage,
    acmeOrgId,
  }) => {
    test.slow(); // the worker has to pick the run up off the stream and walk the graph

    await acmePage.goto(`/app/${acmeOrgId}/tickets`);
    await acmePage.getByRole("link", { name: /Where is my order\? \(Demo\)/ }).click();
    await acmePage.waitForURL(/\/tickets\/[0-9a-f-]{36}$/);
    const ticketUrl = acmePage.url();

    await acmePage.getByRole("button", { name: "Ask the agent" }).click();

    // Starting a run redirects to the run page, which is the first thing proving the server action
    // reached the API and got a run id back rather than failing quietly.
    await acmePage.waitForURL(/\/runs\/[0-9a-f-]{36}$/, { timeout: 30_000 });
    const runUrl = acmePage.url();

    // The run page polls; it should move off QUEUED on its own, without a manual reload. If the
    // worker is not running, this is where the suite fails — the correct place to fail, loudly,
    // rather than later with something cryptic.
    await expect(acmePage.getByText("AWAITING APPROVAL", { exact: true })).toBeVisible({ timeout: 90_000 });

    // The proposal is shown, with its citations, before anyone decides anything.
    await expect(acmePage.getByRole("heading", { name: "Approvals" })).toBeVisible();
    await acmePage.getByRole("link", { name: /Review and decide/ }).click();

    const proposedReply = (await acmePage.locator("blockquote").first().innerText()).trim();
    expect(proposedReply.length).toBeGreaterThan(0);

    await acmePage.getByRole("button", { name: "Approve and publish" }).click();

    await acmePage.goto(runUrl);
    // Exact, because the sanitized trace on this same page contains
    // `{"terminal_status": "COMPLETED"}` — the first run of this test matched both and failed on
    // a strict-mode violation rather than on anything being wrong.
    await expect(acmePage.getByText("COMPLETED", { exact: true })).toBeVisible({
      timeout: 90_000,
    });

    // And the thing that actually matters to a customer: the exact proposed text is now on the
    // ticket as an agent message. Asserting the *same* text, not merely that some message
    // appeared — "an AI-generated draft is never automatically published" cuts both ways, and a
    // published message that differed from what the human approved would be just as wrong.
    await acmePage.goto(ticketUrl);
    // Exactly one. `scripts/e2e-stack.sh` resets the schema before every run, so this is a real
    // count and not a "greater than zero" that would also pass if approving published twice —
    // which, given this product's whole premise, is the failure worth catching.
    await expect(acmePage.getByText(AGENT_MESSAGE.name, { exact: true })).toHaveCount(1, {
      timeout: 30_000,
    });
    await expect(acmePage.getByText(proposedReply, { exact: false })).toBeVisible();
  });

  test("nothing is published while the approval is still pending", async ({
    acmePage,
    acmeOrgId,
  }) => {
    test.slow();

    // The negative half of the same claim, and the one that would catch the worst possible bug in
    // this product: a run started and deliberately left undecided must put nothing on the ticket.
    await acmePage.goto(`/app/${acmeOrgId}/tickets`);
    await acmePage.getByRole("link", { name: /Refund request for order #4821 \(Demo\)/ }).click();
    await acmePage.waitForURL(/\/tickets\/[0-9a-f-]{36}$/);
    const ticketUrl = acmePage.url();

    // Nothing has run against this ticket, and the schema was reset before the suite started.
    await expect(acmePage.getByText(AGENT_MESSAGE.name, { exact: true })).toHaveCount(0);

    await acmePage.getByRole("button", { name: "Ask the agent" }).click();
    await acmePage.waitForURL(/\/runs\/[0-9a-f-]{36}$/, { timeout: 30_000 });
    await expect(acmePage.getByText("AWAITING APPROVAL", { exact: true })).toBeVisible({ timeout: 90_000 });

    await acmePage.goto(ticketUrl);
    await expect(acmePage.getByText(AGENT_MESSAGE.name, { exact: true })).toHaveCount(0);
  });
});
