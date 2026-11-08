import Link from "next/link";
import { redirect } from "next/navigation";
import { fetchMe } from "@/lib/api/me";
import { CreateOrganizationForm } from "./CreateOrganizationForm";

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
    <main id="main-content" tabIndex={-1}>
      <h1>Your organizations</h1>
      {me.organizations.length === 0 ? (
        <>
          <p>{me.displayName} doesn&apos;t belong to an organization yet.</p>
          <CreateOrganizationForm />
        </>
      ) : (
        <ul>
          {me.organizations.map((org) => (
            <li key={org.organizationId}>
              <Link href={`/app/${org.organizationId}/dashboard`}>
                {org.name} ({org.role})
              </Link>
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}
