import { AppShell } from "@/components/shell/AppShell";

// Every part of AppShell (nav hrefs, the org badge) genuinely depends on
// orgId, so there is no meaningful static shell to carve out here — and
// this layout will be rebuilt around real session/membership data in T08/T09
// anyway. `instant = false` tells Cache Components this is an intentional,
// acknowledged choice rather than an unaddressed validation warning. See
// node_modules/next/dist/docs/.../route-segment-config/instant.md.
export const instant = false;

export default async function OrgLayout({ children, params }: LayoutProps<"/app/[orgId]">) {
  const { orgId } = await params;
  return <AppShell orgId={orgId}>{children}</AppShell>;
}
