/**
 * The upload rules services/api enforces (`KnowledgeServiceImpl`), restated so the browser can
 * reject an impossible file before spending a 10 MiB round trip on it, and so the server action
 * can check the same thing before opening an upload session.
 *
 * The server remains the only authority — this module exists for the error message, not for the
 * decision (AGENTS.md rule 11). It deliberately imports nothing: it is the one piece of the
 * knowledge client that is safe to pull into a client component.
 */

/** docs/API_CONTRACT.md: "Upload accept: .txt, .md, .pdf; max default 10 MiB." */
export const MAX_UPLOAD_BYTES = 10 * 1024 * 1024;

/**
 * Extension → the MIME type this client declares for it. The browser's own `File.type` is not
 * usable here: it is empty for `.md` on most platforms and can disagree with the extension, while
 * the backend requires the declared type to match the extension's allowlist.
 */
const MIME_TYPE_BY_EXTENSION: Record<string, string> = {
  txt: "text/plain",
  md: "text/markdown",
  pdf: "application/pdf",
};

export const ALLOWED_EXTENSIONS = Object.keys(MIME_TYPE_BY_EXTENSION);

/** The `accept` attribute for a file input, matching the allowlist above. */
export const UPLOAD_ACCEPT_ATTRIBUTE = ALLOWED_EXTENSIONS.map((e) => `.${e}`).join(",");

/** The declared MIME type for a filename, or `null` when its extension is not accepted. */
export function declaredMimeTypeFor(filename: string): string | null {
  const lastDot = filename.lastIndexOf(".");
  if (lastDot < 0) {
    return null;
  }
  const extension = filename.slice(lastDot + 1).toLowerCase();
  return MIME_TYPE_BY_EXTENSION[extension] ?? null;
}

/** The rejection reason for a file, or `null` when it passes every client-side rule. */
export function rejectionReasonFor(file: { name: string; size: number }): string | null {
  if (!declaredMimeTypeFor(file.name)) {
    return `Unsupported file type. Allowed: ${ALLOWED_EXTENSIONS.map((e) => `.${e}`).join(", ")}.`;
  }
  if (file.size > MAX_UPLOAD_BYTES) {
    return "File exceeds the 10 MiB upload limit.";
  }
  if (file.size === 0) {
    return "That file is empty.";
  }
  return null;
}
