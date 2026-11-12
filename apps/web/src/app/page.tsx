import Link from "next/link";
import { BrandMark } from "@/components/shell/BrandMark";
import styles from "./page.module.css";

/**
 * Marketing/demo intro ("/" per docs/PRODUCT_AND_ARCHITECTURE.md section 5).
 * No live product data belongs here — that starts at /app/[orgId]/dashboard
 * once there is something real to show. The three points below describe
 * behaviour that is actually implemented and enforced server-side; none of
 * them is a claim about adoption, accuracy or performance.
 */
export default function Home() {
  return (
    <div className={styles.page}>
      <main id="main-content" tabIndex={-1} className={styles.hero}>
        <span className={styles.wordmark}>
          <BrandMark size={28} />
          OpsPilot
        </span>

        <h1 className={styles.title}>Support operations, with a human still holding the pen.</h1>

        <p className={styles.tagline}>
          Triage tickets, ground replies in your own knowledge base, and require human approval
          before anything is published.
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

        <ul className={styles.points}>
          <li className={styles.point}>
            <h2 className={styles.pointTitle}>Grounded, or it says so</h2>
            <p className={styles.pointBody}>
              Every draft carries the documents it drew on. A reply with no sources is labelled as
              having none.
            </p>
          </li>
          <li className={styles.point}>
            <h2 className={styles.pointTitle}>Approval is a real gate</h2>
            <p className={styles.pointBody}>
              Nothing reaches a customer until a person approves the exact text. A rejected or
              already-decided proposal cannot be executed.
            </p>
          </li>
          <li className={styles.point}>
            <h2 className={styles.pointTitle}>Tenants stay separate</h2>
            <p className={styles.pointBody}>
              Membership is resolved from the signed-in session, never from the request body — on
              every read and every write.
            </p>
          </li>
        </ul>
      </main>
    </div>
  );
}
