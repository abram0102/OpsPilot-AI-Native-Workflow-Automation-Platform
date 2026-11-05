/**
 * Root loading UI, shown while a route segment streams in. Per
 * docs/PRODUCT_AND_ARCHITECTURE.md section 6: never claim success while
 * something is merely loading — this is a neutral, labeled state only.
 */
export default function Loading() {
  return (
    <div role="status" aria-live="polite" style={{ padding: "3rem 1.5rem", textAlign: "center" }}>
      <p>Loading…</p>
    </div>
  );
}
