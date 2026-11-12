import { redirect } from "next/navigation";
import { fetchMe } from "@/lib/api/me";
import { AcceptInvitationForm } from "./AcceptInvitationForm";
import ui from "@/styles/ui.module.css";
import styles from "@/app/app/page.module.css";

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
    <main id="main-content" tabIndex={-1} className={styles.wrapper}>
      <div className={ui.pageNarrow}>
        <div className={ui.pageHeaderText}>
          <h1>Accept invitation</h1>
          <p className={ui.lead}>
            Joining as {me.displayName}. An invitation works once, and only for the email it was
            issued to.
          </p>
        </div>
        <AcceptInvitationForm defaultToken={typeof token === "string" ? token : ""} />
      </div>
    </main>
  );
}
