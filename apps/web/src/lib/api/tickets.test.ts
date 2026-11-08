import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/lib/auth0", () => ({
  auth0: {
    getSession: vi.fn().mockResolvedValue({ user: { sub: "auth0|test" } }),
    getAccessToken: vi.fn().mockResolvedValue({ token: "test-access-token" }),
  },
}));

vi.mock("@/lib/api/config", () => ({
  getApiBaseUrl: () => "https://api.test.local",
}));

import { createTicket } from "@/lib/api/tickets";

/**
 * T11 acceptance: "create ticket via typed client works against local API and returns
 * server-provided values." Docker isn't available in this dev environment (Gate H0), so this
 * can't be a live round trip here — it proves the typed client builds the real request
 * (method/URL/bearer token/body) and correctly parses the server's response shape, with a mocked
 * `fetch` standing in for services/api.
 */
describe("createTicket", () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
  });

  it("POSTs to the real endpoint with the caller's bearer token and returns the server's values", async () => {
    const serverResponse = {
      id: "11111111-1111-1111-1111-111111111111",
      organizationId: "22222222-2222-2222-2222-222222222222",
      customerId: "33333333-3333-3333-3333-333333333333",
      subject: "Where is my order?",
      description: "It has been five days.",
      status: "OPEN",
      priority: "MEDIUM",
      createdBy: "44444444-4444-4444-4444-444444444444",
      version: 0,
      createdAt: "2026-01-01T00:00:00Z",
      updatedAt: "2026-01-01T00:00:00Z",
    };
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify(serverResponse), {
          status: 201,
          headers: { "Content-Type": "application/json" },
        }),
      );
    vi.stubGlobal("fetch", fetchMock);

    const ticket = await createTicket("22222222-2222-2222-2222-222222222222", {
      customerName: "Sample Customer 01",
      subject: "Where is my order?",
      description: "It has been five days.",
    });

    expect(ticket).toMatchObject({
      id: serverResponse.id,
      status: "OPEN",
      priority: "MEDIUM",
      version: 0,
    });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [request] = fetchMock.mock.calls[0] as [Request];
    expect(request.url).toBe(
      "https://api.test.local/api/v1/organizations/22222222-2222-2222-2222-222222222222/tickets",
    );
    expect(request.method).toBe("POST");
    expect(request.headers.get("Authorization")).toBe("Bearer test-access-token");
    expect(await request.clone().json()).toEqual({
      customerName: "Sample Customer 01",
      subject: "Where is my order?",
      description: "It has been five days.",
    });
  });

  it("throws ApiError built from the stable error envelope on a non-2xx response", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          error: { code: "FORBIDDEN", message: "You cannot perform this action." },
        }),
        { status: 403, headers: { "Content-Type": "application/json" } },
      ),
    );
    vi.stubGlobal("fetch", fetchMock);

    await expect(
      createTicket("org-id", { customerName: "X", subject: "s", description: "d" }),
    ).rejects.toMatchObject({
      status: 403,
      code: "FORBIDDEN",
      message: "You cannot perform this action.",
    });
  });
});
