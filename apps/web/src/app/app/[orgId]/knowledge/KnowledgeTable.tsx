"use client";

import { useRouter } from "next/navigation";
import { useTransition } from "react";
import type { KnowledgeDocument, KnowledgeDocumentStatus } from "@/lib/api/knowledge";
import styles from "./KnowledgeTable.module.css";

interface KnowledgeTableProps {
  documents: KnowledgeDocument[];
  canDelete: boolean;
  deleteAction: (documentId: string) => Promise<void>;
}

const STATUS_CLASS: Record<KnowledgeDocumentStatus, string> = {
  UPLOADING: styles.uploading,
  QUEUED: styles.queued,
  PROCESSING: styles.processing,
  READY: styles.ready,
  FAILED: styles.failed,
};

/**
 * Metadata only — filename, type, size, status. Deliberately never renders any part of a
 * document's contents: uploaded files are untrusted input (AGENTS.md rule 12), and inlining them
 * would be exactly the injection surface docs/SECURITY_AND_TESTING.md warns about.
 */
export function KnowledgeTable({ documents, canDelete, deleteAction }: KnowledgeTableProps) {
  const router = useRouter();
  const [refreshing, startRefresh] = useTransition();

  return (
    <div>
      <button
        type="button"
        onClick={() => startRefresh(() => router.refresh())}
        disabled={refreshing}
      >
        {refreshing ? "Refreshing…" : "Refresh status"}
      </button>

      {documents.length === 0 ? (
        <p>No documents uploaded yet.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>File</th>
              <th>Type</th>
              <th>Size</th>
              <th>Status</th>
              <th>Uploaded</th>
              {canDelete && <th>Actions</th>}
            </tr>
          </thead>
          <tbody>
            {documents.map((document) => (
              <tr key={document.id}>
                <td>{document.filename}</td>
                <td>{document.mimeType}</td>
                <td>{formatSize(document.sizeBytes)}</td>
                <td>
                  <span className={`${styles.badge} ${STATUS_CLASS[document.status]}`}>
                    {document.status}
                  </span>
                  {document.status === "FAILED" && document.errorCode && (
                    <span role="alert"> {document.errorCode}</span>
                  )}
                </td>
                <td>{new Date(document.createdAt).toLocaleString()}</td>
                {canDelete && (
                  <td>
                    <DeleteDocumentForm
                      filename={document.filename}
                      action={deleteAction.bind(null, document.id)}
                    />
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

function DeleteDocumentForm({
  filename,
  action,
}: {
  filename: string;
  action: () => Promise<void>;
}) {
  return (
    <form
      action={action}
      onSubmit={(event) => {
        if (!window.confirm(`Delete "${filename}"? This cannot be undone.`)) {
          event.preventDefault();
        }
      }}
    >
      <button type="submit">Delete</button>
    </form>
  );
}

function formatSize(sizeBytes: number): string {
  if (sizeBytes < 1024) {
    return `${sizeBytes} B`;
  }
  if (sizeBytes < 1024 * 1024) {
    return `${(sizeBytes / 1024).toFixed(1)} KiB`;
  }
  return `${(sizeBytes / (1024 * 1024)).toFixed(1)} MiB`;
}
