# HTTP / Event / Agent Tool Contracts

## Common HTTP conventions

- Base `/api/v1`, JSON camelCase field names externally, ISO 8601 UTC timestamps, UUID strings, standard HTTP status codes.
- Auth: OIDC-signed bearer token verified by Spring Security. Frontend normally calls through Next.js server-side BFF; do not store access tokens in localStorage. API rejects missing/invalid JWT as 401.
- Every organization resource path uses `/organizations/{orgId}/...`. `orgId` is an identifier, NOT authorization; backend validates resolved user membership and required role. A mismatch is consistently 403 or conceal with 404; write one policy and tests.
- For POST commands that may be retried use `Idempotency-Key` header (UUID). Maintain mapping to canonical results (DB unique or dedupe table) so repeated requests do not duplicate runs or published messages.
- Common list response: `{ "items": [...], "nextCursor": "...", "total": null }` (avoid inaccurate total if not computed), size default 25, max 100.
- API errors: `{ "error": { "code": "FORBIDDEN", "message": "You cannot perform this action", "requestId": "...", "details": [] } }`; no stack trace or third-party error secrets.
- API response must be typed via generated OpenAPI; frontend should not independently invent fields.
- Health `/actuator/health/readiness` and `/actuator/health/liveness`, with controlled exposure; public logs use `X-Request-ID`.

## User/org

| Method | Path | Allowed role | Semantics |
|---|---|---|---|
| `GET` | `/api/v1/me` | logged in | Internal user profile and accessible orgs |
| `POST` | `/api/v1/organizations` | authenticated | Creates org + owner membership atomically; 201 |
| `GET` | `/api/v1/organizations/{orgId}` | member | Org details |
| `GET` | `/api/v1/organizations/{orgId}/members` | member | Scoped members list |
| `POST` | `/api/v1/organizations/{orgId}/invitations` | owner/admin | Issues single-use invitation token; in dev display via protected admin UI, no external email required |
| `POST` | `/api/v1/invitations/accept` | authenticated | Verifies hashed token, email binding and expiry; create membership |
| `PATCH` | `/api/v1/organizations/{orgId}/members/{memberId}` | owner/admin w/ guard | Change role without removing last owner |

Example create org request: `{ "name": "Acme Demo", "slug": "acme-demo" }` -> `{ "id": "uuid", "name": "Acme Demo", "slug": "acme-demo", "myRole": "OWNER" }`.

## Tickets

| Method | Path | Allowed role | Semantics |
|---|---|---|---|
| `GET` | `/organizations/{orgId}/tickets?status=&priority=&cursor=&limit=` | member/viewer | Paginate scoped tickets |
| `POST` | `/organizations/{orgId}/tickets` | owner/admin/member | Create ticket; optional customer ID from same tenant |
| `GET` | `/organizations/{orgId}/tickets/{ticketId}` | member/viewer | Detail with tags/messages summary |
| `PATCH` | `/organizations/{orgId}/tickets/{ticketId}` | owner/admin/member | Update `status`, `priority`, `assigneeId` with optimistic version |
| `GET` | `/organizations/{orgId}/tickets/{ticketId}/messages` | member/viewer | Internal conversation |
| `POST` | `/organizations/{orgId}/tickets/{ticketId}/messages` | owner/admin/member | Human-authored internal message only |

Example create request:
```
{
  "customerName": "Sample Customer 01",
  "subject": "Where is my order?",
  "description": "I placed an order five days ago and need a status update.",
  "priority": "MEDIUM"
}
```
Response `201`: `{ "id": "uuid", "organizationId": "uuid", "subject": "...", "status": "OPEN", "priority": "MEDIUM", "version": 0, "createdAt": "..." }`.

## Knowledge

| Method | Path | Role | Semantics |
|---|---|---|---|
| `GET` | `/organizations/{orgId}/knowledge/documents` | member/viewer | List metadata/status only |
| `POST` | `/organizations/{orgId}/knowledge/uploads` | owner/admin/member | Validate proposed filename/mime/size and generate signed PUT URL or local stub |
| `POST` | `/organizations/{orgId}/knowledge/documents/{docId}/complete` | owner/admin/member | Confirm actual object checksum/size, queue ingestion via outbox, 202 |
| `GET` | `/organizations/{orgId}/knowledge/documents/{docId}` | member/viewer | Status and indexing error code |
| `DELETE` | `/organizations/{orgId}/knowledge/documents/{docId}` | owner/admin | Soft/hard delete and object cleanup as policy dictates |

Upload accept: `.txt`, `.md`, `.pdf`; max default 10 MiB (server config). MIME and actual signature validation, no executable files or external URL ingestion. Local storage adapter may keep files under a temporary controlled directory for offline tests; production S3 private bucket, limited signed URL expiry.

## Agent runs, trace, approval

| Method | Path | Role | Semantics |
|---|---|---|---|
| `POST` | `/organizations/{orgId}/tickets/{ticketId}/runs` | owner/admin/member | Creates run + outbox; **202** with runId, status `QUEUED` |
| `GET` | `/organizations/{orgId}/runs?status=&cursor=` | member/viewer | Paginated run explorer |
| `GET` | `/organizations/{orgId}/runs/{runId}` | member/viewer | State, timings, sanitized summary, usage |
| `GET` | `/organizations/{orgId}/runs/{runId}/steps` | member/viewer | Sanitized tool traces and citations, paginated |
| `POST` | `/organizations/{orgId}/runs/{runId}/cancel` | owner/admin/member initiator | Cooperative or immediate valid cancel |
| `GET` | `/organizations/{orgId}/approvals?status=PENDING` | owner/admin | Approval inbox |
| `GET` | `/organizations/{orgId}/approvals/{approvalId}` | owner/admin | Exact proposal + audit |
| `POST` | `/organizations/{orgId}/approvals/{approvalId}/approve` | owner/admin | Atomic decision; queues resume event, 200 |
| `POST` | `/organizations/{orgId}/approvals/{approvalId}/reject` | owner/admin | Atomic decision; queues resume event, 200 |

Run start request `{ "workflow": "SUPPORT_TRIAGE_V1" }` -> `202 { "runId": "uuid", "status": "QUEUED", "pollUrl": "/api/v1/.../runs/uuid" }`.

Approval response example:
```
{
  "id":"uuid", "runId":"uuid", "ticketId":"uuid",
  "actionType":"PUBLISH_INTERNAL_REPLY", "status":"PENDING",
  "proposal": { "body": "Thanks for writing. According to ..." },
  "evidence": [{"documentId":"uuid","chunkIndex":2,"title":"Shipping FAQ"}],
  "createdAt":"..."
}
```

**Approval enforcement:** the proposal is immutable after creation. Approval must not accept a replacement body from frontend. API locks `approvals` row, checks actor role and run tenant, commits decision + outbox in one transaction. Worker later rechecks decision before execution; no side effect occurs merely because approval HTTP returned 200.

## Private internal service API

`POST /internal/v1/agent-actions/execute` — only from a machine-identity-authenticated worker on private network. Body: `{runId, toolCallId, actionType, arguments}`. Values `organizationId`, `ticketId`, target role, or approval status submitted by worker are **untrusted hints**; reconstruct from persisted run, validate target, check allowlist and approval. Supported actions:

- `ADD_INTERNAL_TAG`: arguments `{ tag: string }`; safe internal mutation after deterministic tag allowlist/length limits.
- `PUBLISH_INTERNAL_REPLY`: arguments `{ approvalId: uuid }` only; content taken from immutable approved proposal, not worker request.
- Tool `GET_TICKET` and `SEARCH_KNOWLEDGE` are read-only worker functions with explicit tenant filters. Do not add arbitrary SQL/query/shell/browser-network tools.

Return canonical `{toolCallId, status: "SUCCEEDED"|"REJECTED"|"FAILED", resultReference, alreadyApplied}`. Unique `(run_id,tool_call_id)` and a single DB transaction prevent double publication after retries. Add service audience validation and private routing; reject normal browser/user tokens.

## Redis Stream contracts

Stream: `opspilot:events`, consumer group: `agent-workers`. Message envelope:
```
{
  "eventId": "uuid", "type": "agent.run.requested|agent.run.resume|knowledge.ingest.requested",
  "aggregateId": "uuid", "organizationId": "uuid", "schemaVersion": 1,
  "createdAt": "ISO-8601"
}
```

- Published event payload only contains identifiers; workers reload trusted DB state.
- Outbox publishes entries at least once; consumers de-duplicate via `eventId`/job status and `toolCallId`.
- ACK only after durable state. Paused approval runs may ACK when checkpoint + approval state is committed.
- Retry is exponential backoff, max attempts configurable, dead-letter surfacing. Reclaim abandoned pending entries after lease expiry; alert on backlog.

## AI provider and tool schemas

Python protocols:
```
class LLMProvider(Protocol):
    async def structured_response(self, *, messages, schema, max_output_tokens, timeout_s): ...
class EmbeddingProvider(Protocol):
    async def embed(self, texts: list[str]) -> list[list[float]]: ...
```

Pydantic graph data examples:
- `Classification`: `category` enum `[SHIPPING,BILLING,ACCOUNT,OTHER]`, `urgency` `[LOW,MEDIUM,HIGH]`, `summary` <= 500 chars, `confidence` [0,1].
- `RetrievedChunk`: `document_id`, `chunk_index`, `title`, `text`, `score`.
- `ToolProposal`: `tool_call_id`, `action_type` enum, `arguments` bounded JSON schema, `rationale` <= 500 chars.
- `ReplyDraft`: `body` <= 4000 chars, `source_citations[]` referencing retrieved chunks; no outside citations made up.

The agent never gets a generic arbitrary-function executor. Structured outputs are validated before any tool execution. Invalid outputs cause controlled retry, then terminal failure; prompt injection content is data, never system instruction.

## HTTP error cases to implement

- 400 `VALIDATION_ERROR` malformed payload (includes a missing required query parameter)
- 401 `UNAUTHENTICATED` missing/invalid OIDC token
- 403 `FORBIDDEN` tenant/role mismatch (or 404 for concealment, consistently)
- 404 `NOT_FOUND` unknown resource in authorized scope
- 409 `STATE_CONFLICT` illegal transition/optimistic lock/approval state
- 409 `IDEMPOTENCY_CONFLICT` key reused with different request body
- 413 `FILE_TOO_LARGE`
- 429 `RATE_LIMITED` with retry guidance
- 503 `DEPENDENCY_UNAVAILABLE` for critical service unavailability

Added at T28, after the endpoint sweep found these answering 500 (docs/DECISIONS.md ADR-053). A
client mistake must not read as a server failure: it tells the caller to retry something that
cannot work, and it raises the 5xx rate that docs/SECURITY_AND_TESTING.md §7 alerts on, so a
scanner probing the API looks exactly like an outage.

- 405 `METHOD_NOT_ALLOWED` wrong verb for an existing path
- 415 `UNSUPPORTED_MEDIA_TYPE` body sent as a type the endpoint does not read

## Contract governance

Codex must generate the OpenAPI file from Spring Boot and a typed frontend API client from it. Re-run contract generation in CI; prevent drift. Add contract tests for status, key casing, auth and enum values. Breaking schema changes must be versioned, accompanied by migration and test updates.
