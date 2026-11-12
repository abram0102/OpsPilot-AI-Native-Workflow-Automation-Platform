import { beforeEach, describe, expect, it, vi } from "vitest";

const getSession = vi.fn();
const getAccessToken = vi.fn();

vi.mock("@/lib/auth0", () => ({
  auth0: {
    getSession: (...args: unknown[]) => getSession(...args),
    getAccessToken: (...args: unknown[]) => getAccessToken(...args),
  },
}));

vi.mock("@/lib/api/config", () => ({
  getApiBaseUrl: () => "https://api.test.local",
}));

import { GET } from "./route";

/**
 * T28 security regression for the one hand-written BFF route (docs/SECURITY_AND_TESTING.md §1, "web
 * session boundary").
 *
 * <p>This route is the only place in the frontend where a value the browser chose (`orgId`,
 * `runId`) is turned into a request carrying the caller's access token. Every other page resolves
 * its organization from the session's own membership list before it fetches anything. That makes it
 * the single most plausible spot for an IDOR in this codebase, so its behaviour is pinned here
 * rather than argued for in a comment.
 *
 * <p>The design is deliberately *not* to validate the ids in the BFF: it forwards them and lets
 * services/api resolve membership from the token (AGENTS.md rule 11 — authorization is server-side,
 * never inferred from what the client sent). What these tests prove is that the forwarding is
 * faithful in both directions: the caller's own token goes out, and a refusal comes back as the
 * refusal it was.
 *
 * <p>No live API here — `fetch` stands in for services/api, whose side of the same boundary is
 * covered by `AgentRunHttpIntegrationTest.aForeignOrganizationsRunIsNotReadable`.
 */
describe("GET /api/run-status", () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
    getSession.mockReset();
    getAccessToken.mockReset();
    getSession.mockResolvedValue({ user: { sub: "auth0|test" } });
    getAccessToken.mockResolvedValue({ token: "caller-access-token" });
  });

  function requestFor(query: string) {
    return new Request(`https://web.test.local/api/run-status${query}`);
  }

  it("forwards the caller's own token and the ids it was given, deciding nothing itself", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          id: "r1",
          status: "RUNNING",
          stepsUsed: 3,
          updatedAt: "2026-01-01T00:00:00Z",
        }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      ),
    );
    vi.stubGlobal("fetch", fetchMock);

    const response = await GET(requestFor("?orgId=org-a&runId=run-1"));

    expect(response.status).toBe(200);
    // openapi-fetch hands `fetch` a built Request, so the forwarded URL and headers are read off
    // that rather than off an init object.
    const forwarded = fetchMock.mock.calls[0][0] as Request;
    expect(forwarded.url).toBe("https://api.test.local/api/v1/organizations/org-a/runs/run-1");
    expect(forwarded.headers.get("Authorization")).toBe("Bearer caller-access-token");
  });

  it("returns only the three polling fields, never the rest of the run", async () => {
    // The run detail response carries the trace, the error code and the ticket it belongs to. A
    // polling endpoint that echoed its whole body would quietly become a second, untested read
    // path for everything the run page is careful about.
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          id: "r1",
          organizationId: "org-a",
          ticketId: "t1",
          status: "AWAITING_APPROVAL",
          stepsUsed: 4,
          updatedAt: "2026-01-01T00:00:00Z",
          lastErrorCode: "SOMETHING_INTERNAL",
          totalInputTokens: 900,
        }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      ),
    );
    vi.stubGlobal("fetch", fetchMock);

    const body = await (await GET(requestFor("?orgId=org-a&runId=run-1"))).json();

    expect(Object.keys(body).sort()).toEqual(["status", "stepsUsed", "updatedAt"]);
  });

  it("passes a forbidden run back as 403 rather than masking it as a server error", async () => {
    // A member of the wrong organization must learn nothing beyond "not yours", and the poller
    // must stop. Re-labelling this as 500 would make the client retry forever.
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ error: { code: "FORBIDDEN", message: "Not permitted." } }), {
        status: 403,
        headers: { "Content-Type": "application/json" },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    const response = await GET(requestFor("?orgId=someone-elses-org&runId=run-1"));

    expect(response.status).toBe(403);
    expect((await response.json()).error.code).toBe("FORBIDDEN");
  });

  it("passes a concealed cross-tenant run back as 404", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ error: { code: "NOT_FOUND", message: "No such run." } }), {
        status: 404,
        headers: { "Content-Type": "application/json" },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    const response = await GET(requestFor("?orgId=org-a&runId=a-run-in-org-b"));

    expect(response.status).toBe(404);
    expect((await response.json()).error.code).toBe("NOT_FOUND");
  });

  it("answers 401 without calling the API at all when there is no session", async () => {
    getSession.mockResolvedValue(null);
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const response = await GET(requestFor("?orgId=org-a&runId=run-1"));

    expect(response.status).toBe(401);
    // An unauthenticated poll must not reach services/api on someone else's behalf.
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("rejects a request missing its ids without calling the API", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const response = await GET(requestFor("?orgId=org-a"));

    expect(response.status).toBe(400);
    expect((await response.json()).error.code).toBe("VALIDATION_ERROR");
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
