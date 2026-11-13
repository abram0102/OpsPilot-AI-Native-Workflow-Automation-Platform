import { fileURLToPath } from "node:url";

import { test, expect, createTicket } from "../src/fixtures.js";

/**
 * Captures the screenshots the portfolio README embeds (docs/TASKS.md T35).
 *
 * <p>A test rather than a manual pass with a cropping tool, for one reason: T35's acceptance is
 * "screenshots show working features", and a screenshot taken by hand six weeks ago shows
 * whatever the app looked like six weeks ago. These are produced by driving the real stack
 * through the same fixtures the E2E suite uses, so re-running the suite regenerates them and a
 * feature that broke cannot keep a working-looking picture.
 *
 * <p>Not part of the normal run — `make screenshots` selects it by name — because writing files
 * into docs/ is a side effect no test should have by default.
 */

const SHOTS = "../../docs/images";

/** Resolved from this file, not from the working directory Playwright happens to be started in. */
const KNOWLEDGE_FIXTURE = fileURLToPath(
  new URL("../fixtures/acme-shipping-policy.md", import.meta.url),
);

test.describe("portfolio screenshots", () => {
  test("captures the product surfaces the README shows", async ({ acmePage, acmeOrgId }) => {
    test.slow();

    // 1. Dashboard — counts over real seeded data.
    await acmePage.goto(`/app/${acmeOrgId}/dashboard`);
    await expect(acmePage.getByRole("heading", { name: "Dashboard" })).toBeVisible();
    await acmePage.screenshot({ path: `${SHOTS}/01-dashboard.png`, fullPage: true });

    // 2. The ticket list.
    await acmePage.goto(`/app/${acmeOrgId}/tickets`);
    await expect(acmePage.getByRole("heading", { name: "Tickets" })).toBeVisible();
    await acmePage.screenshot({ path: `${SHOTS}/02-tickets.png`, fullPage: true });

    // 3. Knowledge ingestion. Uploaded here rather than seeded, because the status column is
    //    only meaningful if a document actually travelled the path: Server Action → API →
    //    outbox → Redis → worker → extract, chunk, embed → READY. A seeded row would be a
    //    screenshot of a value someone typed into the database.
    await acmePage.goto(`/app/${acmeOrgId}/knowledge`);
    await acmePage.getByRole("button", { name: "Upload document" }).click();
    await acmePage
      .locator("#knowledge-file")
      .setInputFiles(KNOWLEDGE_FIXTURE);
    await acmePage.getByRole("button", { name: "Upload", exact: true }).click();
    await expect(acmePage.getByRole("status")).toContainText("queued for indexing");
    await acmePage.getByRole("button", { name: "Close" }).click();

    // The worker indexes asynchronously, so the page needs re-reading until it reports READY.
    // This is also the only assertion in the suite that the ingestion pipeline works end to end
    // through the UI rather than through a handler test.
    await expect(async () => {
      await acmePage.getByRole("button", { name: /Refresh status/ }).click();
      await expect(acmePage.getByText("READY", { exact: true })).toBeVisible({ timeout: 2_000 });
    }).toPass({ timeout: 60_000 });
    await acmePage.screenshot({ path: `${SHOTS}/03-knowledge.png`, fullPage: true });

    // 4–7: one ticket carried all the way through the agent flow, so the screenshots tell a
    // single continuous story rather than showing unrelated states. It runs after the upload so
    // the trace has something tenant-scoped to retrieve and cite.
    const ticketUrl = await createTicket(
      acmePage,
      acmeOrgId,
      "Where is my order? It has not moved in six days",
    );
    await expect(acmePage.getByRole("button", { name: "Ask the agent" })).toBeVisible();
    await acmePage.screenshot({ path: `${SHOTS}/04-ticket-detail.png`, fullPage: true });

    await acmePage.getByRole("button", { name: "Ask the agent" }).click();
    await acmePage.waitForURL(/\/runs\/[0-9a-f-]{36}$/, { timeout: 30_000 });
    const runUrl = acmePage.url();
    await expect(acmePage.getByText("AWAITING APPROVAL", { exact: true })).toBeVisible({
      timeout: 90_000,
    });
    // 5. The run explorer: status, step trace, token usage, the proposal and its citations.
    await acmePage.screenshot({ path: `${SHOTS}/05-agent-run-trace.png`, fullPage: true });

    // 6. The approval the human actually decides on.
    await acmePage.getByRole("link", { name: /Review and decide/ }).click();
    await expect(acmePage.getByRole("button", { name: "Approve and publish" })).toBeVisible();
    await acmePage.screenshot({ path: `${SHOTS}/06-approval.png`, fullPage: true });

    await acmePage.getByRole("button", { name: "Approve and publish" }).click();

    // Publishing is asynchronous: approving enqueues the write, and the worker completes the run.
    // The run page polls, so waiting here is what gives the publish time to happen. Going
    // straight to the ticket instead fails in a way worth recording — the ticket page is rendered
    // once on the server, so `toHaveCount`'s retry re-checks a DOM that can never change, and the
    // run simply had not published yet. agent-run.spec.ts waits on COMPLETED for the same reason.
    await acmePage.goto(runUrl);
    await expect(acmePage.getByText("COMPLETED", { exact: true })).toBeVisible({
      timeout: 90_000,
    });

    // 7. The published reply on the ticket — the end of the chain.
    await acmePage.goto(ticketUrl);
    // `toBeVisible`, not `toHaveCount`. During React's streaming SSR the thread is already in the
    // DOM inside a hidden container while `app/loading.tsx` is still the painted frame, so a
    // count assertion passes on a page that shows nothing but "Loading…" — which is precisely the
    // screenshot the first run produced. Visibility is the property a screenshot depends on.
    const agentMessage = acmePage.getByText("Agent", { exact: true });
    await expect(agentMessage).toBeVisible({ timeout: 30_000 });
    await expect(agentMessage).toHaveCount(1);
    await acmePage.screenshot({ path: `${SHOTS}/07-published-reply.png`, fullPage: true });
  });

  test("captures the cross-tenant refusal", async ({ acmePage, globexPage, acmeOrgId }) => {
    // The security property is as much a feature as the agent is, and a screenshot of a refusal
    // is the clearest way to show it.
    await globexPage.goto(`/app/${acmeOrgId}/dashboard`);
    await expect(globexPage.getByRole("heading", { name: "No access" })).toBeVisible();
    await globexPage.screenshot({ path: `${SHOTS}/08-tenant-isolation.png`, fullPage: true });
  });
});
