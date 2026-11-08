import { redirect } from "next/navigation";
import { fetchMe } from "@/lib/api/me";
import { AcceptInvitationForm } from "./AcceptInvitationForm";

export const instant = false;

/**
 * Where an invitation link (from {@code InviteMemberForm}) lands. Requires a real session — a
 * signed-out visitor goes to {@code /sign-in} and has to come back to this same URL themselves;
 * there is no dynamic post-login return-to wiring yet (that's T08's `/sign-in`, not this ticket's
 * job to change).
 */
export default async function AcceptInvitationPage({
  searchParams,
}: PageProps<"/invitations/accept">) {
  const { token } = await searchParams;
  const me = await fetchMe();
  if (!me) {
    redirect("/sign-in");
  }

  return (
    <main id="main-content" tabIndex={-1}>
      <h1>Accept invitation</h1>
      <AcceptInvitationForm defaultToken={typeof token === "string" ? token : ""} />
    </main>
  );
}
