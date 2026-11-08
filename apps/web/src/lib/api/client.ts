import createClient from "openapi-fetch";
import type { paths } from "@contracts/schema";
import { auth0 } from "@/lib/auth0";
import { getApiBaseUrl } from "@/lib/api/config";

export type { paths, components } from "@contracts/schema";

/** A non-2xx response from the API, carrying the stable error envelope's code and message
    (docs/API_CONTRACT.md). */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

interface ApiErrorEnvelope {
  error?: { code?: string; message?: string };
}

/**
 * The caller's own server-resolved Auth0 access token, or `null` when there is no session. This
 * module never reads the session more than once per call and never caches a token across
 * requests/users (security boundary doc: the browser never holds one; nor does this module).
 */
export async function requireAccessToken(): Promise<string | null> {
  const session = await auth0?.getSession();
  if (!session) {
    return null;
  }
  const { token } = await auth0!.getAccessToken();
  return token;
}

/**
 * The BFF's one typed HTTP client into services/api (T11), generated from the real endpoints
 * (packages/contracts). A fresh instance per call is intentional — it's cheap, and it keeps each
 * caller's bearer token from ever being reused for a different request/user.
 */
export function apiClient(token: string) {
  return createClient<paths>({
    baseUrl: getApiBaseUrl(),
    headers: { Authorization: `Bearer ${token}` },
  });
}

/**
 * Unwraps an openapi-fetch result into its data, or throws {@link ApiError} built from the
 * stable error envelope every non-2xx services/api response uses.
 */
export async function unwrap<T>(result: {
  data?: T;
  error?: unknown;
  response: Response;
}): Promise<T> {
  if (result.error !== undefined) {
    const envelope = result.error as ApiErrorEnvelope;
    throw new ApiError(
      result.response.status,
      envelope.error?.code ?? "UNKNOWN",
      envelope.error?.message ?? `Request failed with status ${result.response.status}.`,
    );
  }
  if (result.data === undefined) {
    throw new ApiError(result.response.status, "UNKNOWN", "Empty response body.");
  }
  return result.data;
}
