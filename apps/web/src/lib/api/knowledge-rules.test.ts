import { describe, expect, it } from "vitest";
import { MAX_UPLOAD_BYTES, declaredMimeTypeFor, rejectionReasonFor } from "./knowledge-rules";

describe("declaredMimeTypeFor", () => {
  it("declares a type from the extension, not from whatever the browser guessed", () => {
    // The browser reports "" for .md on most platforms — the backend requires a real type that
    // matches the extension's allowlist, so the extension is the only usable source.
    expect(declaredMimeTypeFor("policy.md")).toBe("text/markdown");
    expect(declaredMimeTypeFor("notes.txt")).toBe("text/plain");
    expect(declaredMimeTypeFor("handbook.pdf")).toBe("application/pdf");
  });

  it("matches the extension case-insensitively", () => {
    expect(declaredMimeTypeFor("HANDBOOK.PDF")).toBe("application/pdf");
  });

  it("rejects an extension outside the allowlist, and a name with no extension at all", () => {
    expect(declaredMimeTypeFor("payload.exe")).toBeNull();
    expect(declaredMimeTypeFor("archive.tar.gz")).toBeNull();
    expect(declaredMimeTypeFor("README")).toBeNull();
  });

  it("reads only the final extension, so a double extension cannot smuggle a type", () => {
    expect(declaredMimeTypeFor("invoice.pdf.exe")).toBeNull();
  });
});

describe("rejectionReasonFor", () => {
  it("accepts a file inside every limit", () => {
    expect(rejectionReasonFor({ name: "policy.md", size: 1024 })).toBeNull();
  });

  it("accepts a file exactly at the size limit, matching the backend's own boundary", () => {
    expect(rejectionReasonFor({ name: "policy.md", size: MAX_UPLOAD_BYTES })).toBeNull();
  });

  it("rejects a file one byte over the limit", () => {
    expect(rejectionReasonFor({ name: "policy.md", size: MAX_UPLOAD_BYTES + 1 })).toBe(
      "File exceeds the 10 MiB upload limit.",
    );
  });

  it("rejects an unsupported type", () => {
    expect(rejectionReasonFor({ name: "payload.exe", size: 10 })).toBe(
      "Unsupported file type. Allowed: .txt, .md, .pdf.",
    );
  });

  it("rejects an empty file", () => {
    expect(rejectionReasonFor({ name: "policy.md", size: 0 })).toBe("That file is empty.");
  });
});
