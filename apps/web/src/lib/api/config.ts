/**
 * The Spring Boot API's base URL. Never hardcode this anywhere else — read
 * it from here, which reads it from the environment (see .env.example at
 * the repository root: NEXT_PUBLIC_API_BASE_URL).
 *
 * The typed client (added in T11) and the server-side BFF handlers (T08+)
 * both import this rather than constructing their own default.
 */
export function getApiBaseUrl(): string {
  return process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";
}
