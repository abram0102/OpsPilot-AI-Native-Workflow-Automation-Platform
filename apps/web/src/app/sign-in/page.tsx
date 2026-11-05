import Link from "next/link";
import styles from "./page.module.css";

/**
 * Whether Auth0 looks configured for this environment. The real OIDC login
 * flow is built in ticket T08 (docs/TASKS.md); until then this must show an
 * honest "not configured" state rather than a fake working login form.
 */
function isAuthConfigured(): boolean {
  return Boolean(process.env.AUTH0_DOMAIN && process.env.AUTH0_CLIENT_ID);
}

export default function SignInPage() {
  const configured = isAuthConfigured();

  return (
    <main id="main-content" tabIndex={-1} className={styles.wrapper}>
      <h1>Sign in</h1>
      {configured ? (
        <p>
          Auth0 environment variables are present, but the actual sign-in flow is implemented in
          ticket T08 — this placeholder does not perform a real login yet.
        </p>
      ) : (
        <div role="status" className={styles.notice}>
          <p>
            <strong>Authentication is not configured yet.</strong>
          </p>
          <p>
            This environment has no Auth0 domain or client configured. See{" "}
            <code>docs/MANUAL_SETUP.md</code> (Gate H1) to set one up, and ticket T08 for the real
            sign-in implementation.
          </p>
        </div>
      )}
      <Link className={styles.back} href="/">
        Back to home
      </Link>
    </main>
  );
}
