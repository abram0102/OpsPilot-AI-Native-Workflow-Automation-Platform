import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

// RTL's automatic afterEach-cleanup only self-registers when it detects a
// global `afterEach` (e.g. Vitest's `globals: true`). We deliberately don't
// enable that globally, so register cleanup explicitly instead — otherwise
// each test file's renders pile up in the same jsdom document.
afterEach(() => {
  cleanup();
});
