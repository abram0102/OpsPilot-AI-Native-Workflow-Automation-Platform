import { ApiError, getRun } from "@/lib/api/runs";

/**
 * The one endpoint the browser polls while a run is in flight (T27).
 *
 * <p>It exists because the browser has no access token — the BFF holds it (T08) — so a client
 * component cannot ask services/api directly. This hands back the three fields the explorer needs
 * to decide whether anything changed, and nothing else: no trace text, no proposal, no identities.
 *
 * <p>`orgId` and `runId` arrive from the client and are treated as untrusted. They are not checked
 * here; they are *forwarded* with the caller's own session token, and services/api resolves
 * membership from that token and answers 403/404 for anything the caller may not see (AGENTS.md
 * rule 11). A status this route copies back is therefore one the API already decided the caller is
 * entitled to.
 */
export async function GET(request: Request) {
  const { searchParams } = new URL(request.url);
  const orgId = searchParams.get("orgId");
  const runId = searchParams.get("runId");

  if (!orgId || !runId) {
    return Response.json(
      { error: { code: "VALIDATION_ERROR", message: "orgId and runId are required." } },
      { status: 400 },
    );
  }

  try {
    const run = await getRun(orgId, runId);
    return Response.json(
      { status: run.status, stepsUsed: run.stepsUsed, updatedAt: run.updatedAt },
      { headers: { "Cache-Control": "no-store" } },
    );
  } catch (error) {
    if (error instanceof ApiError) {
      // Pass the API's own status and stable code through unchanged. Re-labelling a 403 as a 500
      // would make a polling client retry something it will never be allowed to read.
      return Response.json(
        { error: { code: error.code, message: error.message } },
        { status: error.status },
      );
    }
    return Response.json(
      { error: { code: "UNAUTHENTICATED", message: "No session." } },
      { status: 401 },
    );
  }
}
