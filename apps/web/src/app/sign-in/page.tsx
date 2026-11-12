import Link from "next/link";
import { BrandMark } from "@/components/shell/BrandMark";
import { isAuth0Configured } from "@/lib/auth0";
import styles from "./page.module.css";

export default function SignInPage() {
  const configured = isAuth0Configured();

  return (
    <main id="main-content" tabIndex={-1} className={styles.wrapper}>
      <div className={styles.card}>
        <span className={styles.wordmark}>
          <BrandMark size={26} />
          OpsPilot
        </span>

        <h1 className={styles.title}>Sign in</h1>

        {configured ? (
          <>
            <p className={styles.lead}>
              You&apos;ll be redirected to Auth0 and brought straight back.
            </p>
            <a className={styles.primary} href="/auth/login?returnTo=/app">
              Continue with Auth0
            </a>
          </>
        ) : (
          <div role="status" className={styles.notice}>
            <p>
              <strong>Authentication is not configured yet.</strong>
            </p>
            <p>
              This environment has no Auth0 domain or client configured. See{" "}
              <code>docs/MANUAL_SETUP.md</code> (Gate H1) to set one up.
            </p>
          </div>
        )}

        <Link className={styles.back} href="/">
          ← Back to home
        </Link>
      </div>
    </main>
  );
}
