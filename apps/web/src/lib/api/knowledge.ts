import { apiClient, requireAccessToken, unwrap } from "@/lib/api/client";
import { getApiBaseUrl } from "@/lib/api/config";

export { ApiError } from "@/lib/api/client";

/** Mirrors services/api's {@code KnowledgeDocumentStatus}. */
export type KnowledgeDocumentStatus = "UPLOADING" | "QUEUED" | "PROCESSING" | "READY" | "FAILED";

/** Mirrors services/api's {@code KnowledgeDocumentResponse}. */
export interface KnowledgeDocument {
  id: string;
  filename: string;
  mimeType: string;
  sizeBytes: number;
  status: KnowledgeDocumentStatus;
  errorCode?: string;
  createdAt: string;
  updatedAt: string;
}

/** Mirrors services/api's {@code CreateUploadSessionResponse}. */
export interface UploadSession {
  documentId: string;
  objectKey: string;
  uploadUrl: string;
  uploadMethod: string;
}

async function authorizedClient() {
  const token = await requireAccessToken();
  if (!token) {
    throw new Error("Not signed in.");
  }
  return apiClient(token);
}

function toKnowledgeDocument(data: {
  id?: string;
  filename?: string;
  mimeType?: string;
  sizeBytes?: number;
  status?: KnowledgeDocumentStatus;
  errorCode?: string;
  createdAt?: string;
  updatedAt?: string;
}): KnowledgeDocument {
  return {
    id: data.id!,
    filename: data.filename!,
    mimeType: data.mimeType!,
    sizeBytes: data.sizeBytes!,
    status: data.status!,
    errorCode: data.errorCode,
    createdAt: data.createdAt!,
    updatedAt: data.updatedAt!,
  };
}

export async function createUploadSession(
  organizationId: string,
  input: { filename: string; mimeType: string; sizeBytes: number; sha256: string },
): Promise<UploadSession> {
  const client = await authorizedClient();
  const result = await client.POST("/api/v1/organizations/{orgId}/knowledge/uploads", {
    params: { path: { orgId: organizationId } },
    body: input,
  });
  const data = await unwrap(result);
  return {
    documentId: data.documentId!,
    objectKey: data.objectKey!,
    uploadUrl: data.uploadUrl!,
    uploadMethod: data.uploadMethod!,
  };
}

/**
 * Sends the file's bytes to the upload target the session handed back. This is deliberately a raw
 * `fetch` rather than the generated client: the endpoint takes an opaque byte stream, not a typed
 * JSON body, and the target may not even be our own API.
 *
 * Which is exactly why the bearer token is attached only for a *relative* target. The local
 * adapter returns a path back into services/api, which needs the caller's token like any other
 * endpoint; the S3 adapter returns an absolute presigned URL that carries its own signature in the
 * query string, and forwarding our token to a third-party host would both leak it and break S3's
 * signed-header check.
 */
export async function putUploadedBytes(
  session: UploadSession,
  mimeType: string,
  bytes: Uint8Array,
): Promise<void> {
  const isOwnApi = session.uploadUrl.startsWith("/");
  const url = isOwnApi ? `${getApiBaseUrl()}${session.uploadUrl}` : session.uploadUrl;

  const headers: Record<string, string> = { "Content-Type": mimeType };
  if (isOwnApi) {
    const token = await requireAccessToken();
    if (!token) {
      throw new Error("Not signed in.");
    }
    headers.Authorization = `Bearer ${token}`;
  }

  const response = await fetch(url, {
    method: session.uploadMethod,
    headers,
    body: bytes as BodyInit,
  });

  if (!response.ok) {
    let message = `Upload failed with status ${response.status}.`;
    try {
      const envelope = (await response.json()) as { error?: { message?: string } };
      message = envelope.error?.message ?? message;
    } catch {
      // A non-JSON failure (an S3 XML error, say) — the status-based message above stands.
    }
    throw new Error(message);
  }
}

/** Verifies the stored bytes server-side and moves the document to `QUEUED`. */
export async function completeUpload(
  organizationId: string,
  documentId: string,
): Promise<KnowledgeDocument> {
  const client = await authorizedClient();
  const result = await client.POST(
    "/api/v1/organizations/{orgId}/knowledge/documents/{docId}/complete",
    { params: { path: { orgId: organizationId, docId: documentId } } },
  );
  return toKnowledgeDocument(await unwrap(result));
}

export async function listKnowledgeDocuments(organizationId: string): Promise<KnowledgeDocument[]> {
  const client = await authorizedClient();
  const result = await client.GET("/api/v1/organizations/{orgId}/knowledge/documents", {
    params: { path: { orgId: organizationId } },
  });
  return (await unwrap(result)).map(toKnowledgeDocument);
}

export async function getKnowledgeDocument(
  organizationId: string,
  documentId: string,
): Promise<KnowledgeDocument> {
  const client = await authorizedClient();
  const result = await client.GET("/api/v1/organizations/{orgId}/knowledge/documents/{docId}", {
    params: { path: { orgId: organizationId, docId: documentId } },
  });
  return toKnowledgeDocument(await unwrap(result));
}

export async function deleteKnowledgeDocument(
  organizationId: string,
  documentId: string,
): Promise<void> {
  const client = await authorizedClient();
  const result = await client.DELETE("/api/v1/organizations/{orgId}/knowledge/documents/{docId}", {
    params: { path: { orgId: organizationId, docId: documentId } },
  });
  if (result.error !== undefined) {
    await unwrap(result);
  }
}
