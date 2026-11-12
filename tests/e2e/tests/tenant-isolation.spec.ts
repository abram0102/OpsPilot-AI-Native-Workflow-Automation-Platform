import { test, expect, resolveOrgId } from "../src/fixtures.js";

/**
 * Two distinct tenant users, through the browser (docs/SECURITY_AND_TESTING.md §2 E2E;
 * docs/TASKS.md T29 acceptance "relevant UI flows run via Playwright").
 *
 * <p>The API side of this is already covered many times over —
 * `AgentRunHttpIntegrationTest.aForeignOrganizationsRunIsNotReadable` and friends, and T28's
 * endpoint sweep attacks every `{orgId}` route with a stranger. What none of those can tell you is
 * what the *person* sees: whether the BFF forwards the right token, whether a refusal renders as a
 * usable page or as a stack trace, and whether a tenant's name can leak into the chrome of a page
 * that otherwise denies access. That is this file's job.
 */
test.describe("two tenants", () => {
  test("each owner lands in their own organization", async ({ acmePage, globexPage }) => {
    const acmeOrgId = await resolveOrgId(acmePage);
    const globexOrgId = await resolveOrgId(globexPage);

    expect(acmeOrgId).not.toBe(globexOrgId);

    await expect(acmePage.getByRole("heading", { name: "Dashboard" })).toBeVisible();
    await expect(globexPage.getByRole("heading", { name: "Dashboard" })).toBeVisible();
  });

  test("one tenant's owner cannot open the other tenant's pages", async ({
    acmePage,
    globexPage,
  }) => {
    const acmeOrgId = await resolveOrgId(acmePage);

    // Globex's owner, pointed straight at Acme's URL. This is the IDOR attempt an operator could
    // actually make by editing the address bar.
    await globexPage.goto(`/app/${acmeOrgId}/dashboard`);

    await expect(globexPage.getByRole("heading", { name: "No access" })).toBeVisible();
    // Rendered by app/[orgId]/layout.tsx, so it guards every page under the organization rather
    // than each one separately. It names the *signed-in* user and offers a way back — note it
    // does not name the organization that was refused, which is the property asserted below.
    await expect(globexPage.getByText(/is not a member of this organization/)).toBeVisible();
    await expect(globexPage.getByRole("link", { name: "your organizations" })).toBeVisible();

    // And nothing of Acme's leaked onto the refusal page itself.
    await expect(globexPage.locator("body")).not.toContainText("Acme");
  });

  test("a foreign tenant's tickets are not listed or readable", async ({
    acmePage,
    globexPage,
  }) => {
    const acmeOrgId = await resolveOrgId(acmePage);

    await acmePage.goto(`/app/${acmeOrgId}/tickets`);
    await expect(acmePage.getByRole("heading", { name: "Tickets" })).toBeVisible();
    // The seeded Acme tickets are visible to Acme.
    await expect(acmePage.getByText("Where is my order? (Demo)")).toBeVisible();

    const globexOrgId = await resolveOrgId(globexPage);
    await globexPage.goto(`/app/${globexOrgId}/tickets`);
    await expect(globexPage.getByRole("heading", { name: "Tickets" })).toBeVisible();
    // Globex sees its own seeded ticket and none of Acme's.
    await expect(globexPage.getByText("Cannot log in to my account (Demo)")).toBeVisible();
    await expect(globexPage.getByText("Where is my order? (Demo)")).toHaveCount(0);

    // The list route refused; now the detail route, reached directly.
    await globexPage.goto(`/app/${acmeOrgId}/tickets`);
    await expect(globexPage.getByRole("heading", { name: "No access" })).toBeVisible();
    await expect(globexPage.locator("body")).not.toContainText("Where is my order?");
  });

  test("a signed-out visitor is not shown any tenant data", async ({ page }) => {
    // No cookie on this context at all.
    await page.goto("/app");
    await expect(page).toHaveURL(/\/sign-in/);
    await expect(page.locator("body")).not.toContainText("Acme");
    await expect(page.locator("body")).not.toContainText("Globex");
  });
});
