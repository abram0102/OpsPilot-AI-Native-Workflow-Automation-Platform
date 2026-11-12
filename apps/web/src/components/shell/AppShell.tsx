import type { ReactNode } from "react";
import styles from "./AppShell.module.css";
import { BrandMark } from "./BrandMark";
import { NavLinks, type NavItem } from "./NavLinks";

interface AppShellProps {
  orgId: string;
  orgName?: string;
  user?: { displayName: string; email: string };
  children: ReactNode;
}

/**
 * Left nav + narrow top toolbar, per docs/PRODUCT_AND_ARCHITECTURE.md
 * section 6. "Dashboard", "Tickets" (T12), "Knowledge" (T14), "Approvals"
 * (T22) and "Settings" (members/invitations, T09) link somewhere real today;
 * the remaining org-scoped section (the run explorer UI) is added here as its
 * own ticket lands — not as a dead link ahead of time.
 */
export function AppShell({ orgId, orgName, user, children }: AppShellProps) {
  const items: NavItem[] = [
    { href: `/app/${orgId}/dashboard`, label: "Dashboard" },
    { href: `/app/${orgId}/tickets`, label: "Tickets" },
    { href: `/app/${orgId}/knowledge`, label: "Knowledge" },
    { href: `/app/${orgId}/approvals`, label: "Approvals" },
    { href: `/app/${orgId}/settings/members`, label: "Settings" },
  ];

  return (
    <div className={styles.shell}>
      <header className={styles.toolbar}>
        <div className={styles.toolbarGroup}>
          <span className={styles.brand}>
            <BrandMark />
            OpsPilot
          </span>
          <span className={styles.orgBadge}>Org: {orgName ?? orgId}</span>
        </div>
        {user && (
          <span className={styles.account}>
            <span className={styles.accountName}>{user.displayName}</span>
            <a className={styles.signOut} href="/auth/logout">
              Sign out
            </a>
          </span>
        )}
      </header>
      <div className={styles.body}>
        <nav className={styles.nav} aria-label="Primary">
          <NavLinks items={items} />
          <p className={styles.navNote}>
            The run explorer UI is added as its ticket lands (see docs/TASKS.md).
          </p>
        </nav>
        <main id="main-content" tabIndex={-1} className={styles.content}>
          {children}
        </main>
      </div>
    </div>
  );
}
