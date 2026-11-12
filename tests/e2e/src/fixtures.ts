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

export { expect } from "@playwright/test";
export { ACME_OWNER, GLOBEX_OWNER, resolveOrgId };
