# apps/web

Next.js 16 / React 19 / TypeScript frontend. Skeleton implemented in ticket **T04**; **T08** added the real Auth0 OIDC login/logout flow (`@auth0/nextjs-auth0` v4), the authenticated `/app` organization selector, and session-gated `/app/[orgId]/*` layouts. **T09** added a real create-organization form (atomically becoming its owner), an `/app/[orgId]/settings/members` page (member list, invite flow, role management — owner/admin only), and `/invitations/accept`. **T11** rewired every API call onto a typed client (`openapi-fetch` + types generated from `packages/contracts/openapi.json`) instead of hand-rolled `fetch`; `src/lib/api/tickets.ts` is ready for **T12**'s ticket screens. Still honest about what's missing: with no Auth0 application configured (Gate H1), every one of these still starts from `/sign-in`'s "not configured" state rather than a fake login, so none of it has been exercised by a real signed-in browser session yet — only by the backend's own Testcontainers HTTP tests and mocked-`fetch` unit tests of the typed client itself.

## Requirements

- **Node 26** (see `.nvmrc` — `nvm use`). Next.js 16 requires Node `>=20.9.0`; this project pins the current active LTS. If you're on an older Node 20.x via `nvm`, `pnpm test` may fail with an unrelated `undici`/Node ABI crash — switch with `nvm use` first.
- **pnpm**, via Corepack: `corepack enable && corepack prepare pnpm@12.16.1 --activate` (no separate global install needed).

## Commands

```bash
pnpm install --frozen-lockfile
pnpm dev          # http://localhost:3000
pnpm lint
pnpm typecheck    # runs `next typegen` first, then `tsc --noEmit`
pnpm test
pnpm build
pnpm format       # prettier --write
```

## Structure

```
src/proxy.ts              # mounts Auth0's /auth/* routes (Next 16: proxy.ts, not middleware.ts)
src/lib/auth0.ts           # Auth0Client singleton; null when Gate H1 isn't done yet
src/lib/api/client.ts      # the one typed HTTP client (openapi-fetch + packages/contracts types) — T11
src/lib/api/me.ts          # fetchMe() — GET /api/v1/me, through the typed client
src/lib/api/organizations.ts # create org, list members, invite, accept, update role (T09), through the typed client
src/lib/api/tickets.ts     # create/list/get/update tickets + messages, through the typed client — T11, used by T12
src/app/                  # App Router routes
  page.tsx                # "/" landing page
  sign-in/                # real Auth0 login link, or honest "not configured" state
  app/page.tsx            # organization selector (T08) + create-organization form (T09)
  app/[orgId]/layout.tsx  # session + membership gate, then AppShell (instant = false)
  app/[orgId]/dashboard/  # dashboard placeholder, no fake data
  app/[orgId]/settings/members/ # member list, invite flow, role management (T09)
  invitations/accept/     # where an invitation link lands (T09)
  error.tsx, loading.tsx  # root error boundary / loading UI
src/components/shell/     # AppShell (left nav + top toolbar, signed-in user + sign out)
src/lib/api/config.ts     # getApiBaseUrl() — reads NEXT_PUBLIC_API_BASE_URL, never hardcoded
```

## Auth0 (T08)

Login/logout/callback/profile routes are mounted by the SDK itself via `src/proxy.ts` — there's nothing to route by hand. `fetchMe()` is the one place the BFF calls the Spring API: it reads the session, gets an access token scoped to `AUTH0_AUDIENCE`, and forwards it as a bearer token to `GET /api/v1/me`, which is also where a brand-new user gets provisioned server-side (see `docs/DECISIONS.md` ADR-020). Without Gate H1 done, `auth0` (in `src/lib/auth0.ts`) is `null` and every route that depends on it degrades to the honest "not configured" / redirect-to-`/sign-in` path — never a bypass.

## Important: this Next.js version has real breaking changes from training data

Next.js 16 ships Cache Components (`cacheComponents: true` in `next.config.ts`) and a Partial Prerendering model that genuinely differ from Next.js 13/14 conventions. `next dev` auto-generates `apps/web/AGENTS.md` (committed on purpose — it's harmless and regenerates identically) warning exactly about this. **Before writing data-fetching, auth, or caching code here (T08+), read `node_modules/next/dist/docs/01-app/01-getting-started/08-caching.md` and the `instant` / `use cache` reference docs** rather than assuming prior Next.js knowledge applies. See `docs/DECISIONS.md` ADR-009.

## Accessibility

- A "Skip to main content" link is the first focusable element on every page (`globals.css` `.skip-link`).
- `:focus-visible` has a visible outline globally — never remove it without an equally visible replacement.
- The nav is a labeled landmark (`<nav aria-label="Primary">`); content renders inside `<main id="main-content">`.
