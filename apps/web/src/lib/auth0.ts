import { Auth0Client } from "@auth0/nextjs-auth0/server";

/**
 * Whether this environment has a real Auth0 application configured. Checked
 * before ever touching the SDK so a missing Gate H1 setup (docs/MANUAL_SETUP.md)
 * produces an honest "not configured" page (T08 acceptance) instead of the SDK
 * throwing on first use or silently falling back to some bypass.
 */
export function isAuth0Configured(): boolean {
  return Boolean(
    process.env.AUTH0_DOMAIN && process.env.AUTH0_CLIENT_ID && process.env.AUTH0_CLIENT_SECRET,
  );
}

/**
 * The SDK singleton, or `null` when Auth0 isn't configured yet. `audience` must
 * match the API's `API_OIDC_AUDIENCE` (services/api) exactly, or the access
 * token forwarded to it will fail the resource server's audience check.
 */
export const auth0: Auth0Client | null = isAuth0Configured()
  ? new Auth0Client({
      authorizationParameters: {
        scope: "openid profile email",
        audience: process.env.AUTH0_AUDIENCE,
      },
    })
  : null;
