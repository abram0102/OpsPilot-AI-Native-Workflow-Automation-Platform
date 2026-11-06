import Link from "next/link";
import { isAuth0Configured } from "@/lib/auth0";
import styles from "./page.module.css";

export default function SignInPage() {
  const configured = isAuth0Configured();

  return (
    <main id="main-content" tabIndex={-1} className={styles.wrapper}>
      <h1>Sign in</h1>
      {configured ? (
        <a className={styles.back} href="/auth/login?returnTo=/app">
          Continue with Auth0
        </a>
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
        Back to home
      </Link>
    </main>
  );
}
