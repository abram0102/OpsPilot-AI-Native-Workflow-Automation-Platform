import { createPrivateKey, type KeyObject } from "node:crypto";
import { SignJWT } from "jose";
import { generateSessionCookie } from "@auth0/nextjs-auth0/testing";

/**
 * Signed offline test identities (docs/TASKS.md T29).
 *
 * <p>The problem this solves: the browser authenticates through Auth0, not through the API's test
 * issuer, so minting an API token is not enough to be signed in — the session lives in an encrypted
 * cookie the Next.js BFF owns. Three ways out were considered and two rejected:
 *
 * <ul>
 *   <li>Point the SDK at a local mock OIDC server — `Auth0Client` builds `https://${domain}`, so
 *       serving it over http on localhost means fighting the SDK at every step.
 *   <li>Add a test-only sign-in route to the web app — this is exactly the "publicly reachable test
 *       auth shortcut" T28's acceptance forbids, and it would ship in the real bundle.
 *   <li><b>Use the SDK's own `./testing` export</b> — `generateSessionCookie` is maintained by the
 *       SDK, pinned in our lockfile, and lives only in this package. Nothing test-only reaches the
 *       application.
 * </ul>
 *
 * <p>The access token inside that cookie is signed with the test issuer's key, which the API
 * accepts only when `app.security.allow-test-issuer=true` — never in production, where
 * `ProductionSafetyGuard` refuses to start at all. The key itself is generated per run by
 * `scripts/e2e-stack.sh` and handed to both processes as an environment variable, so no key
 * material is committed.
 */

/** Must match `TestIssuerConfig.TEST_ISSUER` in services/api. */
export const TEST_ISSUER = "https://test-issuer.opspilot.local/";

/** Must match `UserProvisioningService.EMAIL_CLAIM` / `NAME_CLAIM`. */
const EMAIL_CLAIM = "https://opspilot.local/email";
const NAME_CLAIM = "https://opspilot.local/name";

export interface TestIdentity {
  /** The `auth_subject` the API resolves a membership from. */
  readonly sub: string;
  readonly email: string;
  readonly name: string;
  /** Human label for test titles, e.g. "Acme owner". */
  readonly label: string;
}

/**
 * The two tenants `DemoDataSeeder` creates. Deliberately the seeded subjects rather than ones this
 * harness invents: a test that provisions its own user proves nothing about the data an operator
 * actually gets from `make seed-demo`, and the two-tenant isolation case needs two real tenants
 * that exist independently of each other.
 */
export const ACME_OWNER: TestIdentity = {
  sub: "seed|acme-owner",
  email: "owner@acme-demo.example",
  name: "Acme Owner (Demo)",
  label: "Acme owner",
};

export const GLOBEX_OWNER: TestIdentity = {
  sub: "seed|globex-owner",
  email: "owner@globex-demo.example",
  name: "Globex Owner (Demo)",
  label: "Globex owner",
};

function requiredEnv(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(
      `${name} is not set. Run the E2E suite through \`make test-e2e\`, which generates the ` +
        `test issuer key and starts the stack with it — running \`playwright test\` directly ` +
        `cannot work, because the API has to have been started with the same key.`,
    );
  }
  return value;
}

function signingKey(): KeyObject {
  const base64Pkcs8 = requiredEnv("E2E_TEST_ISSUER_PRIVATE_KEY").replace(/\s/g, "");
  return createPrivateKey({
    key: Buffer.from(base64Pkcs8, "base64"),
    format: "der",
    type: "pkcs8",
  });
}

const TOKEN_LIFETIME_SECONDS = 60 * 30;

/**
 * An access token the API will accept for this identity.
 *
 * <p>Carries the two namespaced profile claims an Auth0 Action supplies in the real deployment
 * (docs/MANUAL_SETUP.md Gate H1), because `UserProvisioningService` reads them to provision the
 * user on first sight. Without them a first login still succeeds but lands on the synthetic
 * placeholder fallback, which is a different code path from the one an operator exercises.
 */
export async function accessTokenFor(identity: TestIdentity): Promise<string> {
  const audience = process.env.E2E_API_AUDIENCE ?? "https://api.opspilot.local";
  return new SignJWT({
    [EMAIL_CLAIM]: identity.email,
    [NAME_CLAIM]: identity.name,
  })
    .setProtectedHeader({ alg: "RS256" })
    .setIssuer(TEST_ISSUER)
    .setSubject(identity.sub)
    .setAudience(audience)
    .setIssuedAt()
    .setExpirationTime(`${TOKEN_LIFETIME_SECONDS}s`)
    .sign(signingKey());
}

/**
 * The encrypted `appSession` cookie value for this identity.
 *
 * <p>Encrypted with the same `AUTH0_SECRET` the web app runs with — that is the whole mechanism,
 * and it is why this cannot be used against any environment whose secret you do not already hold.
 */
export async function sessionCookieFor(identity: TestIdentity): Promise<string> {
  const accessToken = await accessTokenFor(identity);
  const expiresAt = Math.floor(Date.now() / 1000) + TOKEN_LIFETIME_SECONDS;

  return generateSessionCookie(
    {
      user: {
        sub: identity.sub,
        email: identity.email,
        name: identity.name,
        email_verified: true,
      },
      tokenSet: {
        accessToken,
        expiresAt,
        scope: "openid profile email",
      },
    },
    { secret: requiredEnv("E2E_AUTH0_SECRET") },
  );
}

/** The cookie in the shape Playwright's `context.addCookies` wants. */
export async function browserCookieFor(identity: TestIdentity, baseUrl: string) {
  const { hostname } = new URL(baseUrl);
  return {
    name: "__session",
    value: await sessionCookieFor(identity),
    domain: hostname,
    path: "/",
    httpOnly: true,
    secure: false,
    sameSite: "Lax" as const,
  };
}
