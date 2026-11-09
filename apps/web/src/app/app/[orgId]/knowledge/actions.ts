"use server";

import { createHash } from "node:crypto";
import { revalidatePath } from "next/cache";
import {
  ApiError,
  completeUpload,
  createUploadSession,
  deleteKnowledgeDocument,
  putUploadedBytes,
} from "@/lib/api/knowledge";
import { declaredMimeTypeFor, rejectionReasonFor } from "@/lib/api/knowledge-rules";

export interface UploadDocumentState {
  error?: string;
  uploadedFilename?: string;
}

/**
 * Backs {@link UploadDocumentDialog}, bound to {@code orgId} by the server component that renders
 * it. The whole three-step flow (create session → send bytes → complete) runs here, server-side,
 * on purpose: the browser never holds an access token (docs/PRODUCT_AND_ARCHITECTURE.md's BFF
 * boundary), and the local storage adapter's upload target is an authenticated services/api
 * endpoint rather than a public URL. Keeping the bytes on this side also means the S3 adapter's
 * presigned URL will work through the same path with no browser CORS story at all.
 */
export async function uploadDocumentAction(
  orgId: string,
  _prevState: UploadDocumentState,
  formData: FormData,
): Promise<UploadDocumentState> {
  const file = formData.get("file");
  if (!(file instanceof File) || file.size === 0) {
    return { error: "Choose a file to upload." };
  }

  // Mirrors services/api's own checks so an impossible upload fails before the bytes move. The
  // server re-validates all of it regardless, including the actual bytes (AGENTS.md rule 11), and
  // the browser's check is only a courtesy — this one is what the client component cannot skip.
  const rejection = rejectionReasonFor(file);
  if (rejection) {
    return { error: rejection };
  }
  const mimeType = declaredMimeTypeFor(file.name)!;

  const bytes = new Uint8Array(await file.arrayBuffer());
  const sha256 = createHash("sha256").update(bytes).digest("hex");

  try {
    const session = await createUploadSession(orgId, {
      filename: file.name,
      mimeType,
      sizeBytes: bytes.byteLength,
      sha256,
    });
    await putUploadedBytes(session, mimeType, bytes);
    await completeUpload(orgId, session.documentId);
  } catch (error) {
    if (error instanceof ApiError) {
      return { error: error.message };
    }
    if (error instanceof Error) {
      return { error: error.message };
    }
    throw error;
  }

  revalidatePath(`/app/${orgId}/knowledge`);
  return { uploadedFilename: file.name };
}

/**
 * Backs each row's delete button, bound to {@code orgId}/{@code documentId} by the server
 * component. Only ADMIN and above may delete; the backend enforces that regardless of what this
 * page chose to render, and a thrown {@link ApiError} surfaces via the nearest error boundary.
 */
export async function deleteDocumentAction(orgId: string, documentId: string): Promise<void> {
  await deleteKnowledgeDocument(orgId, documentId);
  revalidatePath(`/app/${orgId}/knowledge`);
}
