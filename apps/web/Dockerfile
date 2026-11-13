# syntax=docker/dockerfile:1
#
# Next.js BFF (docs/TASKS.md T32).
#
# This is the public face of the deployment — the thing the ALB routes to — so it holds the Auth0
# session and is the only service a browser talks to. The Spring API sits behind it on the private
# network (docs/DEPLOYMENT_RUNBOOK.md §4).
#
# BUILD CONTEXT IS THE REPOSITORY ROOT, not apps/web:
#
#     docker build -f apps/web/Dockerfile .
#
# The app imports the generated OpenAPI client through `@contracts/*`, which tsconfig maps to
# ../../packages/contracts/src. A context of apps/web cannot see it — Docker refuses to copy from
# above the context, by design — so the context is the root and both directories are copied in.

FROM node:26-bookworm-slim AS deps

# pnpm via npm, not Corepack. Node 26 no longer ships it, so `corepack enable` exits 127 inside
# this image. CI's web job gets away with that only because it runs the command before
# actions/setup-node, on the runner's older preinstalled Node — the image has no such luxury, and
# the difference cost a CI round trip to find.
#
# Pinned to the version in package.json's `packageManager` field. A floating pnpm could resolve
# the committed lockfile differently, which is the one thing a lockfile exists to prevent.
RUN npm install --global pnpm@12.16.1
WORKDIR /app

# Dependencies as their own layer. pnpm-workspace.yaml carries the minimatch override T28 added,
# so it has to be here for the install to resolve the same way it does in CI.
COPY apps/web/package.json apps/web/pnpm-lock.yaml apps/web/pnpm-workspace.yaml ./
RUN --mount=type=cache,target=/pnpm-store \
    pnpm config set store-dir /pnpm-store \
    && pnpm install --frozen-lockfile

# --- build ---------------------------------------------------------------------------------------

FROM node:26-bookworm-slim AS build

# Same reasoning as the deps stage above.
RUN npm install --global pnpm@12.16.1
WORKDIR /app

COPY --from=deps /app/node_modules ./node_modules

# The contracts package first, at the path tsconfig expects relative to apps/web: the app is at
# /app, so ../../packages/contracts resolves to /packages/contracts.
COPY packages/contracts /packages/contracts
COPY apps/web ./

# Production build. NEXT_TELEMETRY_DISABLED because a build should not phone home, and in a
# network-restricted builder the attempt is just a delay.
ENV NEXT_TELEMETRY_DISABLED=1 \
    NODE_ENV=production

RUN pnpm build

# --- runtime -------------------------------------------------------------------------------------

FROM node:26-bookworm-slim AS runtime

# uid/gid 10001, not 1000. The base images disagree about who owns 1000: Ubuntu Noble (the API's
# JRE base) ships a default `ubuntu` user there and node:26 ships `node` there, so `groupadd
# --gid 1000` exits 4, "GID already in use". Debian slim happens to leave it free, which is
# exactly the kind of inconsistency that makes one image fail and another pass for no visible
# reason — CI caught it on the API and the web image would have failed the same way one step
# later. A high, fixed id is free on all three and collides with no host user either.
RUN groupadd --gid 10001 opspilot \
    && useradd --uid 10001 --gid 10001 --create-home --shell /usr/sbin/nologin opspilot

WORKDIR /app

ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0

# HOSTNAME=0.0.0.0 matters: the standalone server binds 127.0.0.1 by default, which inside a
# container means nothing outside it can connect and the load balancer's health check fails with
# no error anywhere in the logs.

# Only what the server needs. `standalone` already contains the trimmed node_modules; `static`
# and `public` are served from disk and are not inside it.
COPY --from=build --chown=10001:10001 /app/.next/standalone ./
COPY --from=build --chown=10001:10001 /app/.next/static ./.next/static
COPY --from=build --chown=10001:10001 /app/public ./public

USER 10001:10001

EXPOSE 3000

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD node -e "fetch('http://127.0.0.1:3000/sign-in').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

CMD ["node", "server.js"]
