# tests/e2e

Playwright end-to-end tests driving the real stack — web, API and worker — against two distinct
tenants, with signed offline test identities (docs/TASKS.md **T29**).

## Running them

```bash
make infra-up          # or point POSTGRES_*/REDIS_* at your own servers
make test-e2e
```

`make test-e2e` runs `scripts/e2e-stack.sh`, which generates a throwaway test-issuer key pair and
session secret, starts the API, seeds the two demo tenants, starts the worker in `AI_MODE=mock`,
builds and starts the web app, waits for each to answer, runs Playwright, and tears the lot down.

Running `pnpm exec playwright test` directly **cannot work**, and says so: the API has to have been
started with the same test-issuer key the tests sign with, and only the script generates it.

The suite uses a database of its own (`opspilot_e2e` by default, `E2E_POSTGRES_DB` to change it).
It seeds tenants and creates tickets, so pointing it at a development database would mix test rows
into real work — and, worse, would make a failing test look like a data problem.

## How sign-in works

The browser authenticates through Auth0, so there is no form to fill in and no password to type —
the real sign-in is a redirect to a hosted page on a third-party production host, which a test must
not automate. Instead `src/identity.ts` mints an access token signed by the API's test issuer and
wraps it in a session cookie using **the SDK's own `generateSessionCookie`** from
`@auth0/nextjs-auth0/testing`.

Two alternatives were rejected: pointing the SDK at a local mock OIDC server (it builds
`https://${domain}`, so http on localhost means fighting it at every turn), and adding a test-only
sign-in route to the web app — which is exactly the "publicly reachable test auth shortcut" T28's
acceptance forbids, and it would ship in the real bundle. Nothing test-only reaches the
application; the whole mechanism lives in this package.

The token is accepted only because `app.security.allow-test-issuer=true` under the `local` profile.
`ProductionSafetyGuard` refuses to start the API if that flag is ever on in production, and
`ProductionSafetyGuardTest` holds that.

## What is covered

| File | Flow |
|---|---|
| `tests/tenant-isolation.spec.ts` | Two owners, two tenants; a cross-tenant URL shows "No access" and leaks no tenant name; a signed-out visitor is redirected |
| `tests/tickets.spec.ts` | Create a ticket in the dialog, find it in the list, open it; the status filter narrows without losing the tenant |
| `tests/agent-run.spec.ts` | Ask the agent → the worker drafts → approve → the **exact** proposed text is published on the ticket; and nothing is published while the approval is pending |

## Artifacts

Traces and screenshots are kept only on failure and are never committed — a trace records the
rendered page, which for a signed-in session includes the session cookie. CI uploads them as a
7-day artifact when the job fails.
