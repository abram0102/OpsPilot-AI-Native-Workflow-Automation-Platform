# Human Setup Gates — Actions Codex Must Not Pretend to Complete

Most coding work can be automated. These steps require personal account control, consent or billing. Codex may generate exact scripts/Terraform and guide execution, but must not assert success until the user does the step and provides non-secret confirmation.

## Gate H0 — Workstation (before T01)

1. Install Git, Docker Desktop, Node.js LTS compatible with the chosen Next.js, Java 21 JDK, Python 3.12, and a package manager (`pnpm`, `uv`, Maven wrapper). Check tools with `git --version`, `docker version`, `node -v`, `java -version`, `python3 --version`.
2. Create a new GitHub repo **private initially**, clone it and copy this blueprint. Commit docs; configure GitHub branch protection later.
3. For local Postgres, no managed DB or database GUI is required: `docker compose up -d postgres redis` will initialize the development database and extension via code-managed migration.
4. Never copy secrets into chat transcripts, issue trackers, or repository commits.

## Gate H1 — Auth0 (before real login T08/T10 E2E)

1. Create/authenticate an Auth0 account. Create a development tenant and a **Regular Web Application** (not SPA) for `apps/web`. Create an API resource with audience `https://api.opspilot.local` (or whatever you set `API_OIDC_AUDIENCE`/`AUTH0_AUDIENCE` to — they must match exactly) for Spring Boot access tokens.
2. **Authorize the Application for that API.** Creating the API resource is not enough — the two are linked separately. In Applications → Applications → your app → **API Access**, authorize `OpsPilot API`. Without it Auth0 refuses the authorization request itself with `invalid_request: Client "<client-id>" is not authorized to access resource server "https://api.opspilot.local".` — the browser lands back on `/auth/callback?error=invalid_request` before any token is ever issued. No scopes need to be selected; the API's authorization rules key off `aud`, not scopes. (Found by walking the flow on 2026-11-11; this step was missing from this document.)
3. In the Application's settings, set **Allowed Callback URLs** to `http://localhost:3000/auth/callback` and **Allowed Logout URLs** to `http://localhost:3000` — the `@auth0/nextjs-auth0` v4 SDK actually installed here drops the `/api` prefix v3 docs still show (confirmed from the SDK's own bundled `node_modules/@auth0/nextjs-auth0/skills/SKILL.md`, not assumed from memory).
4. Set these exact environment variables. **The five `AUTH0_*` ones and `APP_BASE_URL` belong in `apps/web/.env.local`, not the repository root `.env`** — Next.js reads `.env*` relative to its own directory, so a root `.env` never reaches the web app (`make api-dev` sources that one for the Java service only). The API's `API_OIDC_ISSUER_URI` goes in the root `.env`, and unlike `AUTH0_DOMAIN` it takes the scheme and a trailing slash. A real deployment puts the same values in its secrets manager instead. These are v4's variable names, not v3's (see `docs/DECISIONS.md`):
   ```
   AUTH0_DOMAIN=your-tenant.auth0.com   # no scheme
   AUTH0_CLIENT_ID=...
   AUTH0_CLIENT_SECRET=...
   AUTH0_AUDIENCE=https://api.opspilot.local
   AUTH0_SECRET=$(openssl rand -hex 32)
   APP_BASE_URL=http://localhost:3000
   ```
   and in the root `.env`:
   ```
   API_OIDC_ISSUER_URI=https://your-tenant.auth0.com/   # scheme AND trailing slash
   API_OIDC_AUDIENCE=https://api.opspilot.local         # must equal AUTH0_AUDIENCE exactly
   ```
5. **Add a Post-Login Action** so the access token Spring Boot actually validates carries a verified email/name — by default Auth0 only puts those on the ID token, which never leaves the Next.js BFF. In the Auth0 Dashboard, add a Post-Login Action (Actions → Library → Create Action, trigger **Login / Post Login**), Deploy it, then bind it under Actions → **Triggers** → Login / Post Login — the dashboard renamed "Flows" to "Triggers", and a DEPLOYED action that is not bound to the trigger never runs:
   ```js
   exports.onExecutePostLogin = async (event, api) => {
     api.accessToken.setCustomClaim("https://opspilot.local/email", event.user.email);
     api.accessToken.setCustomClaim("https://opspilot.local/name", event.user.name);
   };
   ```
   Without this, first-login provisioning (`UserProvisioningService`) still works but falls back to a synthetic `sub@users.opspilot.local` placeholder rather than the real email — functional, not fake, but not what you want for a real demo account.
6. Configure a dedicated test account and later a second account; avoid public real data.
7. To enable E2E without live Auth0, use a **test-only signed OIDC issuer / token fixture** restricted to test builds, not a dev bypass exposed on production routes — already built in T07/T08 (`TestIssuerConfig`, `TestJwtSigner`), gated by `API_ALLOW_TEST_ISSUER` and refused outright under the `production` profile (`ProductionSafetyGuard`).
8. User confirms login works; Codex implements integration and tests.

## Gate H2 — LLM provider (before real AI T20)

1. Create an API key with explicit monthly or project spend cap; select currently available model IDs and validate embedding dimension compatibility with migration (default vector(1536)).
2. Store as local env and managed production secret. Confirm data policies are appropriate for synthetic demo text.
3. CI uses `AI_MODE=mock` and MUST NOT need an LLM key. Real-LLM eval runs only with explicit permission and budget limit.

## Gate H3 — AWS development/staging (before T31)

1. Create or sign in to AWS account; set billing budgets and alert thresholds before deploying anything.
2. Choose region, create/authorize an IAM deployment role with least privilege; enable GitHub OIDC trust scoped to the exact repository/branch/environment. Avoid static AWS keys in GitHub.
3. Review Terraform/CDK plan for VPC, subnets, ALB/API, ECS Fargate, ECR, RDS PostgreSQL (supported pgvector), private S3, CloudWatch, secret storage, Redis/managed alternative, security groups, DNS/TLS. Human must approve estimated charges and `apply`.
4. RDS creation, service accounts, secrets and private networking may be scripted by infrastructure-as-code; they still require your AWS credentials, cost approval and access controls.
5. Test pgvector extension compatibility and migration on staging first. Configure DB backup and encryption; keep DB nonpublic.
6. Own the domain/DNS if used; add valid HTTPS certificate. Record public staging URL only after verifying deployment.

## Gate H4 — Production release (before T33)

1. Review PR, migrations, AWS costs and deploy plan; approve staging smoke and failure drill.
2. Approve protected GitHub environment deployment, set domain and DNS, ensure TLS and nonpublic DB/Redis endpoints.
3. Perform backup/restore drill; verify monitoring and budget limits before inviting external users.
4. Keep all demo data fictional. No sensitive customers or real communications unless formal privacy/security obligations are addressed.

## Gate H5 — Optional real integrations (after V1)

Gmail/Slack OAuth and outgoing communication need provider app registration, scope authorization, webhook verification, data retention and explicit consent. The v1 project deliberately does not depend on them. Do not treat missing optional credentials as a blocker for the requested portfolio V1.

## Cost control

- Local Docker services are the default. Real LLM calls and cloud hosting can incur charges; no costs estimated as guaranteed.
- Require explicit approval before running `terraform apply`, increasing cloud service sizes, bulk LLM eval or paid domain purchase.
- Use staging auto-shutdown when practical; set AWS Budget alerts. Cleanup must be reviewed to avoid deleting data unexpectedly.
