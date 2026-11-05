# apps/web

Next.js 16 / React 19 / TypeScript frontend. Skeleton implemented in ticket **T04** (`docs/TASKS.md`): responsive shell (left nav + top toolbar), landing page, a sign-in placeholder with an honest "not configured" state (Auth0 lands in T08), root error boundary and loading UI, and `/app/[orgId]/dashboard` showing no fake data. No backend connection, auth, or typed API client yet — those are T08/T11.

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
src/app/                  # App Router routes
  page.tsx                # "/" landing page
  sign-in/                # login placeholder, "not configured" until T08
  app/[orgId]/layout.tsx  # AppShell wrapper (instant = false — see file comment)
  app/[orgId]/dashboard/  # dashboard placeholder, no fake data
  error.tsx, loading.tsx  # root error boundary / loading UI
src/components/shell/     # AppShell (left nav + top toolbar)
src/lib/api/config.ts     # getApiBaseUrl() — reads NEXT_PUBLIC_API_BASE_URL, never hardcoded
```

## Important: this Next.js version has real breaking changes from training data

Next.js 16 ships Cache Components (`cacheComponents: true` in `next.config.ts`) and a Partial Prerendering model that genuinely differ from Next.js 13/14 conventions. `next dev` auto-generates `apps/web/AGENTS.md` (committed on purpose — it's harmless and regenerates identically) warning exactly about this. **Before writing data-fetching, auth, or caching code here (T08+), read `node_modules/next/dist/docs/01-app/01-getting-started/08-caching.md` and the `instant` / `use cache` reference docs** rather than assuming prior Next.js knowledge applies. See `docs/DECISIONS.md` ADR-009.

## Accessibility

- A "Skip to main content" link is the first focusable element on every page (`globals.css` `.skip-link`).
- `:focus-visible` has a visible outline globally — never remove it without an equally visible replacement.
- The nav is a labeled landmark (`<nav aria-label="Primary">`); content renders inside `<main id="main-content">`.
