import { defineConfig, devices } from "@playwright/test";

/**
 * Playwright configuration for the OpsPilot E2E suite (docs/TASKS.md T29).
 *
 * <p>There is deliberately no `webServer` block. The API has to be started *with* the test
 * issuer's private key and the web app with the matching session secret, and Playwright cannot
 * generate a key before the server it starts — so `scripts/e2e-stack.sh` owns bringing the stack
 * up, and this file assumes it is already there. Running `playwright test` directly fails with an
 * explicit message from `src/identity.ts` rather than a confusing connection error.
 */
const DEVICE_FOR = {
  chromium: "Desktop Chrome",
  firefox: "Desktop Firefox",
  webkit: "Desktop Safari",
} as const;

type BrowserName = keyof typeof DEVICE_FOR;

const requested = process.env.E2E_BROWSER ?? "chromium";
if (!(requested in DEVICE_FOR)) {
  throw new Error(
    `E2E_BROWSER must be one of ${Object.keys(DEVICE_FOR).join(", ")} — got "${requested}".`,
  );
}
const BROWSER = requested as BrowserName;

export default defineConfig({
  testDir: "./tests",
  // The screenshot spec writes PNGs into docs/ and is only wanted when regenerating them
  // (`make screenshots`), so it is excluded from the normal run. A test with a side effect on
  // the repository should never be something you get by accident.
  testIgnore: process.env.E2E_INCLUDE_SCREENSHOTS ? [] : ["**/screenshots.spec.ts"],
  // A tenant-isolation test and an agent-run test can interleave safely — they touch different
  // tenants and different tickets — but they share one seeded database, so full parallelism across
  // files is not free. One worker locally keeps failures readable; CI gets the same for
  // determinism, since the suite is small and the stack, not the tests, dominates the runtime.
  workers: 1,
  fullyParallel: false,
  // A test that only passes on a retry is a test that is telling you something. Locally that
  // should be visible immediately; in CI one retry absorbs genuine infrastructure noise (a cold
  // JIT, a slow container) without hiding a real flake, because `retries > 0` marks the result
  // "flaky" in the report rather than "passed".
  retries: process.env.CI ? 1 : 0,
  forbidOnly: !!process.env.CI,
  timeout: 60_000,
  expect: { timeout: 15_000 },
  reporter: process.env.CI
    ? [["list"], ["html", { open: "never" }], ["github"]]
    : [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: process.env.E2E_WEB_BASE_URL ?? "http://127.0.0.1:3000",
    // Kept only for failures. A trace records the page, which for a signed-in session includes the
    // rendered tenant data — so these are never committed (see .gitignore) and CI uploads them as
    // a short-retention artifact.
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "off",
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
  },
  // One browser at a time, chosen by E2E_BROWSER (default chromium) rather than by `--project`.
  // `make test-e2e` cannot forward `--project=...` — make would read it as a goal, not an
  // argument — and the nightly matrix needs to pick a browser per job, so the selection is an
  // environment variable that both paths can set. Only the selected browser has to be installed,
  // which is also why a PR run does not download three of them.
  projects: [
    {
      name: BROWSER,
      use: { ...devices[DEVICE_FOR[BROWSER]] },
    },
  ],
});
