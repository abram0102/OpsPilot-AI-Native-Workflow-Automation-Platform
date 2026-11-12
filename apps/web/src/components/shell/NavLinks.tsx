"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import styles from "./AppShell.module.css";

export interface NavItem {
  href: string;
  label: string;
}

/**
 * The primary nav, as a Client Component purely so it can mark the current
 * section. `aria-current="page"` is the part that matters — the gold rail and
 * lighter background are a visual echo of it, not the signal itself, so the
 * nav still says where you are with styles disabled or to a screen reader.
 *
 * Matching is by prefix, because a section's inner pages (a ticket detail, an
 * approval detail) should keep their parent lit.
 */
export function NavLinks({ items }: { items: NavItem[] }) {
  const pathname = usePathname();

  return (
    <ul className={styles.navList}>
      {items.map((item) => {
        const active = pathname === item.href || pathname?.startsWith(`${item.href}/`);
        return (
          <li key={item.href}>
            <Link
              href={item.href}
              className={active ? styles.navLinkActive : styles.navLink}
              aria-current={active ? "page" : undefined}
            >
              {item.label}
            </Link>
          </li>
        );
      })}
    </ul>
  );
}
