import { afterEach, describe, expect, it } from "vitest";
import { getApiBaseUrl } from "./config";

describe("getApiBaseUrl", () => {
  const original = process.env.NEXT_PUBLIC_API_BASE_URL;

  afterEach(() => {
    if (original === undefined) {
      delete process.env.NEXT_PUBLIC_API_BASE_URL;
    } else {
      process.env.NEXT_PUBLIC_API_BASE_URL = original;
    }
  });

  it("falls back to the documented local default when unset", () => {
    delete process.env.NEXT_PUBLIC_API_BASE_URL;
    expect(getApiBaseUrl()).toBe("http://localhost:8080");
  });

  it("reads the value from the environment when set, never hardcoding it", () => {
    process.env.NEXT_PUBLIC_API_BASE_URL = "https://api.staging.example.com";
    expect(getApiBaseUrl()).toBe("https://api.staging.example.com");
  });
});
