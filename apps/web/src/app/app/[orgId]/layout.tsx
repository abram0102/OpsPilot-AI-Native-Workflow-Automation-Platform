import { redirect } from "next/navigation";
import { AppShell } from "@/components/shell/AppShell";
import { fetchMe } from "@/lib/api/me";

// Every part of AppShell (nav hrefs, the org badge) genuinely depends on
// orgId, so there is no meaningful static shell to carve out here, and this
// layout fetches session/membership data on every request. `instant = false`
// tells Cache Components this is an intentional, acknowledged choice rather
// than an unaddressed validation warning. See
// node_modules/next/dist/docs/.../route-segment-config/instant.md.
export const instant = false;

export default async function OrgLayout({ children, params }: LayoutProps<"/app/[orgId]">) {
  const { orgId } = await params;
  const me = await fetchMe();

  if (!me) {
    redirect("/sign-in");
  }

  // The backend enforces real tenant/membership checks on every API call
  // regardless (AGENTS.md rule 11) — this is only an honest, early UX guard
  // against rendering a shell for an org the signed-in user doesn't belong to.
  const membership = me.organizations.find((org) => org.organizationId === orgId);
  if (!membership) {
    return (
      <main id="main-content" tabIndex={-1}>
        <h1>No access</h1>
        <p>
          {me.displayName} is not a member of this organization. Go back to{" "}
          <a href="/app">your organizations</a>.
        </p>
      </main>
    );
  }

  return (
    <AppShell
      orgId={orgId}
      orgName={membership.name}
      user={{ displayName: me.displayName, email: me.email }}
    >
      {children}
    </AppShell>
  );
}
