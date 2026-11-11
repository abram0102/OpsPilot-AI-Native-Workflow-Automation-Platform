import Link from "next/link";
import type { ReactNode } from "react";
import styles from "./AppShell.module.css";

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
  return (
    <div className={styles.shell}>
      <header className={styles.toolbar}>
        <div className={styles.toolbarGroup}>
          <span className={styles.brand}>OpsPilot</span>
          <span className={styles.orgBadge}>Org: {orgName ?? orgId}</span>
        </div>
        {user && (
          <span className={styles.account}>
            {user.displayName} <a href="/auth/logout">Sign out</a>
          </span>
        )}
      </header>
      <div className={styles.body}>
        <nav className={styles.nav} aria-label="Primary">
          <ul>
            <li>
              <Link href={`/app/${orgId}/dashboard`}>Dashboard</Link>
            </li>
            <li>
              <Link href={`/app/${orgId}/tickets`}>Tickets</Link>
            </li>
            <li>
              <Link href={`/app/${orgId}/knowledge`}>Knowledge</Link>
            </li>
            <li>
              <Link href={`/app/${orgId}/approvals`}>Approvals</Link>
            </li>
            <li>
              <Link href={`/app/${orgId}/settings/members`}>Settings</Link>
            </li>
          </ul>
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
