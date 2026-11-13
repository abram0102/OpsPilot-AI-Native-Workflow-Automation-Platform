import { test as base, type Page } from "@playwright/test";
import { ACME_OWNER, GLOBEX_OWNER, browserCookieFor, type TestIdentity } from "./identity.js";

/**
 * A page already signed in as one of the two seeded tenants' owners.
 *
 * <p>Signing in by setting the session cookie rather than by driving a login form is not a
 * shortcut taken to save time — there is no form to drive. The real sign-in is an Auth0 redirect
 * to a hosted page on a third-party production host, which a test must not automate and which
 * would make the suite depend on a live external service. What this exercises instead is
 * everything downstream of the session: the BFF reading it, forwarding the token, and the API
 * resolving membership from that token's subject.
 */
interface TenantFixtures {
  acmePage: Page;
  globexPage: Page;
  /** The organization id for the tenant the page is signed in as, read from the app itself. */
  acmeOrgId: string;
}

async function signIn(page: Page, identity: TestIdentity, baseURL: string) {
  await page.context().addCookies([await browserCookieFor(identity, baseURL)]);
}

/**
 * Follows the app's own organization routing to find the signed-in tenant's id.
 *
 * <p>Read from the running app rather than queried from the database or hardcoded: the id is a
 * generated UUID, and a test that looked it up another way would still pass if the app's own
 * org-selection path were broken.
 */
async function resolveOrgId(page: Page): Promise<string> {
  await page.goto("/app");
  await page.waitForURL(/\/app\/[0-9a-f-]{36}\//, { timeout: 30_000 });
  const match = /\/app\/([0-9a-f-]{36})\//.exec(page.url());
  if (!match) {
    throw new Error(`Could not resolve an organization id from ${page.url()}`);
  }
  return match[1];
}

export const test = base.extend<TenantFixtures>({
  acmePage: async ({ browser, baseURL }, use) => {
    const context = await browser.newContext();
    const page = await context.newPage();
    await signIn(page, ACME_OWNER, baseURL!);
    await use(page);
    await context.close();
  },

  globexPage: async ({ browser, baseURL }, use) => {
    const context = await browser.newContext();
    const page = await context.newPage();
    await signIn(page, GLOBEX_OWNER, baseURL!);
    await use(page);
    await context.close();
  },

  acmeOrgId: async ({ acmePage }, use) => {
    await use(await resolveOrgId(acmePage));
  },
});

/**
 * Creates a ticket through the UI and returns its URL.
 *
 * <p>Exists because the agent-run tests must not share a ticket with anything else, including an
 * earlier repeat of themselves. They assert exact message counts — "exactly one agent message",
 * "no agent message" — which is the right assertion: a "greater than zero" would also pass if
 * approving published twice, and given what this product promises that is the failure worth
 * catching.
 *
 * <p>The nightly flake hunt is what made this necessary. `scripts/e2e-stack.sh` resets the schema
 * once per run, so the tests were clean the first time and wrong the second: `--repeat-each=3`
 * runs each test three times inside a single run, and repeat two saw repeat one's published
 * reply. Both agent tests failed on two of their three repeats. The state those tests depend on
 * is now created by the test itself, so repetition and ordering cannot affect it.
 */
export async function createTicket(page: Page, orgId: string, subject: string): Promise<string> {
  await page.goto(`/app/${orgId}/tickets`);
  await page.getByRole("button", { name: "Create ticket" }).click();

  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Customer name").fill("E2E Customer (Test)");
  await dialog.getByLabel("Subject").fill(subject);
  await dialog
    .getByLabel("Description")
    .fill("My parcel has not arrived and the tracking has not updated. Raised by the E2E suite.");
  await dialog.getByLabel("Priority").selectOption("MEDIUM");
  await dialog.getByRole("button", { name: /^Create$|Creating/ }).click();

  await page.waitForURL(/\/tickets\/[0-9a-f-]{36}$/, { timeout: 30_000 });
  return page.url();
}

export { expect } from "@playwright/test";
export { ACME_OWNER, GLOBEX_OWNER, resolveOrgId };
