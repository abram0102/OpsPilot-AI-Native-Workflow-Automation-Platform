import { test, expect } from "../src/fixtures.js";

/**
 * Creating and finding a ticket through the UI (docs/TASKS.md T29: "UI create-ticket …").
 *
 * <p>`TicketHttpIntegrationTest` already proves the endpoint. What it cannot prove is that the
 * dialog's form field names match what the server action sends, that the action's redirect lands
 * somewhere real, or that a newly created ticket actually appears in the list the user is looking
 * at — three things that have no server-side test and break silently in a refactor.
 */
test.describe("tickets", () => {
  test("a ticket created in the dialog appears in the list and opens", async ({
    acmePage,
    acmeOrgId,
  }) => {
    // Unique per run, so repeated local runs against the same seeded database cannot collide or
    // pass on a leftover row from last time.
    const subject = `E2E smoke ticket ${Date.now()}`;

    await acmePage.goto(`/app/${acmeOrgId}/tickets`);
    await acmePage.getByRole("button", { name: "Create ticket" }).click();

    // Scoped to the dialog: the tickets table has its own "Subject contains…" filter, so an
    // unscoped getByLabel("Subject") matches two elements and fails on strict mode.
    const dialog = acmePage.getByRole("dialog");
    await dialog.getByLabel("Customer name").fill("E2E Customer (Test)");
    await dialog.getByLabel("Subject").fill(subject);
    await dialog.getByLabel("Description").fill("Raised by the Playwright suite.");
    await dialog.getByLabel("Priority").selectOption("HIGH");
    await dialog.getByRole("button", { name: /^Create$|Creating/ }).click();

    // The server action redirects to the ticket it just created, which is the part worth pinning:
    // a redirect built from a returned id is exactly what breaks silently in a refactor.
    await acmePage.waitForURL(/\/tickets\/[0-9a-f-]{36}$/, { timeout: 30_000 });
    await expect(acmePage.getByRole("heading", { name: subject })).toBeVisible();
    await expect(acmePage.getByText("Raised by the Playwright suite.")).toBeVisible();

    // And it is in the list the user goes back to.
    await acmePage.getByRole("link", { name: "← Back to tickets" }).click();
    await expect(acmePage.getByRole("link", { name: subject })).toBeVisible({ timeout: 30_000 });
  });

  test("the status filter narrows the list without losing the tenant", async ({
    acmePage,
    acmeOrgId,
  }) => {
    await acmePage.goto(`/app/${acmeOrgId}/tickets`);
    await acmePage.getByLabel("Status").selectOption("RESOLVED");

    // FilterForm submits on change (T27), so this is a navigation, not a button press.
    await acmePage.waitForURL(/status=RESOLVED/, { timeout: 15_000 });
    await expect(acmePage.getByRole("heading", { name: "Tickets" })).toBeVisible();

    // The seeded tickets are OPEN, so a RESOLVED filter should show none of them — and crucially
    // must not fall back to showing everything, which is what a filter dropped on the floor looks
    // like.
    await expect(acmePage.getByText("Where is my order? (Demo)")).toHaveCount(0);
  });
});
