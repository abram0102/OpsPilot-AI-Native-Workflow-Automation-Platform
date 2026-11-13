import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

import { test, expect } from "../src/fixtures.js";

/**
 * Knowledge ingestion, through the browser and all the way to READY (docs/TASKS.md T14–T16).
 *
 * <p>Why this exists as an E2E test rather than only as the Python indexer's own unit tests:
 * ingestion crosses two processes with two working directories. The API writes the uploaded
 * object; the worker reads it back. T35 was the first thing to drive that path through the UI,
 * and it failed on every document with `INDEX_OBJECT_MISSING` — `STORAGE_LOCAL_DIR` was the
 * relative `.local-storage`, which meant `services/api/.local-storage` to one process and
 * `services/agent/.local-storage` to the other. Every unit test passed throughout, because each
 * side was individually correct.
 *
 * <p>So the assertion here is deliberately end-to-end and deliberately in the default suite: a
 * document uploaded by a human reaches READY, or CI fails.
 */

const POLICY = readFileSync(
  fileURLToPath(new URL("../fixtures/acme-shipping-policy.md", import.meta.url)),
);

test.describe("knowledge ingestion", () => {
  test("a document uploaded in the browser is indexed by the worker", async ({
    acmePage,
    acmeOrgId,
  }) => {
    test.slow(); // extraction, chunking and embedding happen on another process

    // A name of its own, so the row this test asserts on is its own even when the suite is
    // repeated against a stack that is only reset once per run.
    const filename = `shipping-policy-${Date.now()}-${Math.random().toString(36).slice(2, 8)}.md`;

    await acmePage.goto(`/app/${acmeOrgId}/knowledge`);
    await acmePage.getByRole("button", { name: "Upload document" }).click();
    await acmePage.locator("#knowledge-file").setInputFiles({
      name: filename,
      mimeType: "text/markdown",
      buffer: POLICY,
    });
    await acmePage.getByRole("button", { name: "Upload", exact: true }).click();

    // The upload itself only promises the bytes were accepted and an event was written.
    await expect(acmePage.getByRole("status")).toContainText("queued for indexing");
    await acmePage.getByRole("button", { name: "Close" }).click();

    const row = acmePage.getByRole("row").filter({ hasText: filename });
    await expect(row).toHaveCount(1);

    // The status column is the whole point: PROCESSING means the event was published, READY means
    // the worker found the object, extracted it, chunked it and wrote embeddings. Only the second
    // one says the two processes agree about where the file is.
    await expect(async () => {
      await acmePage.getByRole("button", { name: /Refresh status/ }).click();
      await expect(row.getByText("READY", { exact: true })).toBeVisible({ timeout: 2_000 });
    }).toPass({ timeout: 90_000 });

    // And not a failure wearing a status badge: FAILED renders its error code in an alert.
    await expect(row.getByRole("alert")).toHaveCount(0);
  });
});
