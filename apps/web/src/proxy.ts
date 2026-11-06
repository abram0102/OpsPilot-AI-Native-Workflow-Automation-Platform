import { NextResponse, type NextRequest } from "next/server";
import { auth0, isAuth0Configured } from "@/lib/auth0";

/**
 * Mounts the Auth0 SDK's own routes (/auth/login, /auth/callback, /auth/logout,
 * /auth/profile) and refreshes the session cookie on every request. `proxy.ts`,
 * not `middleware.ts` — the latter is deprecated in this Next.js version (see
 * node_modules/next/dist/docs/.../file-conventions/middleware.md).
 *
 * When Auth0 isn't configured yet (Gate H1 not done), this must not throw or
 * silently pretend auth works — it passes every request through unchanged, and
 * /sign-in shows the honest "not configured" state instead.
 */
export async function proxy(request: NextRequest) {
  if (!isAuth0Configured()) {
    return NextResponse.next();
  }
  return auth0!.middleware(request);
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico|sitemap.xml|robots.txt).*)"],
};
