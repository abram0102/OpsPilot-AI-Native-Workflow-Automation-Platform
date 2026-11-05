import Link from "next/link";
import styles from "./page.module.css";

/**
 * Marketing/demo intro ("/" per docs/PRODUCT_AND_ARCHITECTURE.md section 5).
 * No live product data belongs here — that starts at /app/[orgId]/dashboard
 * once there is something real to show.
 */
export default function Home() {
  return (
    <div className={styles.page}>
      <main id="main-content" tabIndex={-1} className={styles.hero}>
        <h1 className={styles.title}>OpsPilot</h1>
        <p className={styles.tagline}>
          An AI-native support operations platform: triage tickets, ground replies in your own
          knowledge base, and require human approval before anything is published.
        </p>
        <div className={styles.ctas}>
          <Link className={styles.primary} href="/sign-in">
            Sign in
          </Link>
          <a
            className={styles.secondary}
            href="https://github.com/abram0102/OpsPilot-AI-Native-Workflow-Automation-Platform"
            target="_blank"
            rel="noopener noreferrer"
          >
            View source
          </a>
        </div>
      </main>
    </div>
  );
}
