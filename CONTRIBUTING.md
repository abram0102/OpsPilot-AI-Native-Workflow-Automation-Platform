# Contributing to OpsPilot

This project is built incrementally, one ticket from `docs/TASKS.md` at a time, under the rules in `AGENTS.md`. Read that file first — it outranks the conventions below if they ever conflict.

## Line endings and formatting

- LF everywhere (`.gitattributes` normalizes this on checkout). Do not commit CRLF.
- UTF-8 only. Final newline required; trailing whitespace trimmed (`.editorconfig` enforces this in supporting editors).
- Indentation: 2 spaces for TS/JS/JSON/YAML/Markdown/shell, 4 spaces for Java and Python, tabs for `Makefile` recipe lines (Make requires this).

## Naming conventions

- **Directories / files:** `kebab-case` for TypeScript/React files and routes, `snake_case` for Python modules, standard Java package/class casing (`com.opspilot.<module>`).
- **Database:** `snake_case` table and column names, plural table names (`tickets`, `knowledge_chunks`), singular enum type names.
- **API fields:** `camelCase` externally in every JSON payload (see `docs/API_CONTRACT.md`), even though the database uses `snake_case`.
- **Branches:** `t<NN>-short-description` (e.g. `t01-docker-compose-infra`) so a branch name maps to the ticket it implements.
- **Env vars:** `UPPER_SNAKE_CASE`, grouped by service prefix where it helps (`API_`, `AGENT_`, `WEB_`).

## Version pin policy

- Every ecosystem (Maven, pnpm, Python) commits a lockfile; CI installs with `--frozen-lockfile` / equivalent, never a floating resolve.
- Pick actively maintained, mutually compatible stable releases per `docs/DEPENDENCY_DECISIONS.md`'s baseline targets, verified against each ecosystem's *current* official docs at install time (not memorized versions).
- Record the exact version selected, install date, and lockfile path in `docs/DEPENDENCY_DECISIONS.md` the first time a dependency is introduced.
- Dependency *upgrades* are their own ticket/PR with a smoke test, not a side effect of an unrelated feature change.

## Secret hygiene

- Real secrets never enter the repository, a commit message, an issue, or a chat transcript. `.env` is gitignored; only `.env.example` (placeholders only) is committed.
- CI and local dev default to `AI_MODE=mock` and a test-only signed OIDC fixture — never a real API key or production credential.
- If a secret is ever accidentally committed, treat it as compromised: rotate it, then clean history — do not just delete the file in a new commit.
- Before every commit, review `git status`/`git diff` for anything that looks like a credential, even in a file whose name looks innocuous.

## Conventional commits

One commit message per completed ticket, following [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <short summary>

<optional body: what changed and why>
```

- `type`: `feat`, `fix`, `chore`, `docs`, `test`, `refactor`, `ci`, `build`, `perf`.
- `scope`: the ticket or module, e.g. `feat(infra): T01 docker compose for postgres+pgvector and redis`.
- Body explains *why*, not a restatement of the diff.
- Do not bundle unrelated tickets into one commit. Do not commit unless the operator (you) explicitly asks for it — see `AGENTS.md` rule 19.

## Pull request / review flow

- One ticket per PR/review. Use the "PR review prompt" and "Clean-room proof prompt" in `docs/CODEX_OPERATOR_PROMPT.md` before merging a phase.
- A ticket is not done until its `docs/TASKS.md` acceptance checks have actually been run and recorded in `docs/PROGRESS.md` — not assumed.
