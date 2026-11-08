import { redirect } from "next/navigation";
import { fetchMe } from "@/lib/api/me";
import { fetchMembers, type Member, type MembershipRole } from "@/lib/api/organizations";
import { createInvitationAction, updateMemberRoleAction } from "./actions";
import { InviteMemberForm } from "./InviteMemberForm";

export const instant = false;

const ASSIGNABLE_ROLES: MembershipRole[] = ["VIEWER", "MEMBER", "ADMIN", "OWNER"];

/**
 * Member list + invite flow + role management (docs/PRODUCT_AND_ARCHITECTURE.md:
 * "/app/[orgId]/settings/members roles and invite flow"; docs/TASKS.md T09). The backend is the
 * actual authority on every check here (AGENTS.md rule 11) — `canManage`/`canGrantOwner` below
 * only decide what this page shows, never what it allows.
 */
export default async function MembersPage({ params }: PageProps<"/app/[orgId]/settings/members">) {
  const { orgId } = await params;
  const me = await fetchMe();
  if (!me) {
    redirect("/sign-in");
  }

  const membership = me.organizations.find((org) => org.organizationId === orgId);
  if (!membership) {
    return (
      <main id="main-content" tabIndex={-1}>
        <h1>No access</h1>
        <p>You are not a member of this organization.</p>
      </main>
    );
  }

  const members = await fetchMembers(orgId);
  const canManage = membership.role === "ADMIN" || membership.role === "OWNER";
  const canGrantOwner = membership.role === "OWNER";
  const ownerCount = members.filter((m) => m.role === "OWNER").length;

  return (
    <div>
      <h1>Members</h1>
      <table>
        <thead>
          <tr>
            <th>Name</th>
            <th>Email</th>
            <th>Role</th>
            {canManage && <th>Change role</th>}
          </tr>
        </thead>
        <tbody>
          {members.map((member) => (
            <tr key={member.membershipId}>
              <td>{member.displayName}</td>
              <td>{member.email}</td>
              <td>{member.role}</td>
              {canManage && (
                <td>
                  <MemberRoleForm
                    orgId={orgId}
                    member={member}
                    canGrantOwner={canGrantOwner}
                    isOnlyOwner={member.role === "OWNER" && ownerCount <= 1}
                  />
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
      {canManage && (
        <InviteMemberForm
          action={createInvitationAction.bind(null, orgId)}
          canGrantOwner={canGrantOwner}
        />
      )}
    </div>
  );
}

function MemberRoleForm({
  orgId,
  member,
  canGrantOwner,
  isOnlyOwner,
}: {
  orgId: string;
  member: Member;
  canGrantOwner: boolean;
  isOnlyOwner: boolean;
}) {
  if (isOnlyOwner) {
    // The backend rejects this anyway (docs/DATA_MODEL.md: "last OWNER cannot be removed") —
    // this just avoids offering a control that can only ever fail.
    return <span>Last owner</span>;
  }

  return (
    <form action={updateMemberRoleAction.bind(null, orgId, member.membershipId)}>
      <label htmlFor={`role-${member.membershipId}`}>Role for {member.displayName}</label>
      <br />
      <select id={`role-${member.membershipId}`} name="role" defaultValue={member.role}>
        {ASSIGNABLE_ROLES.filter((role) => role !== "OWNER" || canGrantOwner).map((role) => (
          <option key={role} value={role}>
            {role}
          </option>
        ))}
      </select>
      <button type="submit">Update</button>
    </form>
  );
}
