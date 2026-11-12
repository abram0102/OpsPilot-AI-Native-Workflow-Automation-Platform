import styles from "./status.module.css";

/**
 * Root loading UI, shown while a route segment streams in. Per
 * docs/PRODUCT_AND_ARCHITECTURE.md section 6: never claim success while
 * something is merely loading — this is a neutral, labeled state only.
 */
export default function Loading() {
  return (
    <div role="status" aria-live="polite" className={styles.centered}>
      <p className={styles.pulse}>Loading…</p>
    </div>
  );
}
