import { createRequire } from "node:module";

import type { Page } from "@playwright/test";

import { test, expect, createTicket } from "../src/fixtures.js";

/**
 * Accessibility smoke over the signed-in surfaces (docs/TASKS.md T36, "UX/a11y smoke").
 *
 * <p>axe-core is injected from `node_modules`, not from a CDN: a test that needs the internet is
 * a test that fails on a train, and CI runs offline by design here.
 *
 * <p><b>What this does and does not claim.</b> axe finds a minority of real accessibility
 * problems — roughly the machine-checkable ones: contrast, names, roles, landmarks, form labels.
 * It cannot tell you whether the page is usable with a screen reader or sane to navigate by
 * keyboard. Passing this is a floor, not a certificate, and the release report says so rather
 * than claiming the product is accessible.
 *
 * <p>The gate is `critical` and `serious` only. `moderate` and `minor` findings are printed so
 * they are visible and can be triaged, but they do not fail the build: a smoke test that fails on
 * a best-practice advisory gets disabled within a month, and then nothing is checked at all.
 */

const require = createRequire(import.meta.url);
const AXE_PATH = require.resolve("axe-core/axe.min.js");

/** Tags worth gating on: WCAG 2.1 A and AA, which is the usual legal and practical bar. */
const TAGS = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"];

interface Violation {
  id: string;
  impact: string | null;
  help: string;
  nodes: { target: string[] }[];
}

async function audit(page: Page, label: string): Promise<Violation[]> {
  await page.addScriptTag({ path: AXE_PATH });
  const result = await page.evaluate(async (tags) => {
    // @ts-expect-error axe is injected into the page, not imported here.
    return await window.axe.run(document, { runOnly: { type: "tag", values: tags } });
  }, TAGS);

  const violations = result.violations as Violation[];
  for (const violation of violations) {
    const where = violation.nodes.map((node) => node.target.join(" ")).join(", ");
    console.log(`[a11y] ${label}: ${violation.impact} — ${violation.id}: ${violation.help} (${where})`);
  }
  return violations;
}

function blocking(violations: Violation[]): Violation[] {
  return violations.filter((v) => v.impact === "critical" || v.impact === "serious");
}

test.describe("accessibility smoke", () => {
  test("the signed-in surfaces have no critical or serious axe violations", async ({
    acmePage,
    acmeOrgId,
  }) => {
    test.slow();

    const found: Violation[] = [];

    await acmePage.goto(`/app/${acmeOrgId}/dashboard`);
    await expect(acmePage.getByRole("heading", { name: "Dashboard" })).toBeVisible();
    found.push(...blocking(await audit(acmePage, "dashboard")));

    await acmePage.goto(`/app/${acmeOrgId}/tickets`);
    await expect(acmePage.getByRole("heading", { name: "Tickets" })).toBeVisible();
    found.push(...blocking(await audit(acmePage, "tickets")));

    await acmePage.goto(`/app/${acmeOrgId}/knowledge`);
    await expect(acmePage.getByRole("heading", { name: "Knowledge" })).toBeVisible();
    found.push(...blocking(await audit(acmePage, "knowledge")));

    // A ticket of its own, so the detail page has the agent button and the reply form on it.
    await createTicket(acmePage, acmeOrgId, `A11y smoke ${Date.now()}`);
    await expect(acmePage.getByRole("button", { name: "Ask the agent" })).toBeVisible();
    found.push(...blocking(await audit(acmePage, "ticket detail")));

    expect(
      found.map((v) => `${v.impact} ${v.id} @ ${v.nodes.map((n) => n.target.join(" ")).join(", ")}`),
    ).toEqual([]);
  });

  test("the sign-in page is reachable and clean before any session exists", async ({ page }) => {
    await page.goto("/sign-in");
    const violations = blocking(await audit(page, "sign-in"));
    expect(violations.map((v) => `${v.impact} ${v.id}`)).toEqual([]);
  });
});
