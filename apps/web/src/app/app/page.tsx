import Link from "next/link";
import { redirect } from "next/navigation";
import { fetchMe } from "@/lib/api/me";
import { StatusBadge } from "@/components/ui/StatusBadge";
import ui from "@/styles/ui.module.css";
import { CreateOrganizationForm } from "./CreateOrganizationForm";
import styles from "./page.module.css";

export const instant = false;

/**
 * Organization selector (T08) + creation (T09). A signed-in user with exactly
 * one organization skips straight to its dashboard; with none, they get a
 * real create-organization form (atomically becoming its owner — see
 * services/api's {@code OrganizationServiceImpl}).
 */
export default async function AppIndexPage() {
  const me = await fetchMe();

  if (!me) {
    redirect("/sign-in");
  }

  if (me.organizations.length === 1) {
    redirect(`/app/${me.organizations[0].organizationId}/dashboard`);
  }

  return (
    <main id="main-content" tabIndex={-1} className={styles.wrapper}>
      <div className={ui.pageNarrow}>
        <div className={ui.pageHeaderText}>
          <h1>Your organizations</h1>
          {me.organizations.length === 0 ? (
            <p className={ui.lead}>
              {me.displayName} doesn&apos;t belong to an organization yet. Create one to get a
              workspace of your own — you become its owner.
            </p>
          ) : (
            <p className={ui.lead}>Signed in as {me.displayName}. Choose a workspace to open.</p>
          )}
        </div>

        {me.organizations.length === 0 ? (
          <CreateOrganizationForm />
        ) : (
          <ul className={styles.orgList}>
            {me.organizations.map((org) => (
              <li key={org.organizationId}>
                <Link className={styles.orgCard} href={`/app/${org.organizationId}/dashboard`}>
                  <span className={styles.orgName}>{org.name}</span>
                  <StatusBadge value={org.role} />
                </Link>
              </li>
            ))}
          </ul>
        )}
      </div>
    </main>
  );
}
