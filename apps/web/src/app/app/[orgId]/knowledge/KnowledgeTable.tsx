"use client";

import { useRouter } from "next/navigation";
import { useTransition } from "react";
import type { KnowledgeDocument, KnowledgeDocumentStatus } from "@/lib/api/knowledge";
import ui from "@/styles/ui.module.css";
import styles from "./KnowledgeTable.module.css";

interface KnowledgeTableProps {
  documents: KnowledgeDocument[];
  canDelete: boolean;
  deleteAction: (documentId: string) => Promise<void>;
}

// Ingestion has its own vocabulary, so it keeps its own pill tones rather than
// reusing StatusBadge's ticket/approval table — but the shared `ui.badge*`
// classes mean the shapes and colours still match the rest of the app.
const STATUS_CLASS: Record<KnowledgeDocumentStatus, string> = {
  UPLOADING: ui.badgeWarning,
  QUEUED: ui.badge,
  PROCESSING: ui.badgeWarning,
  READY: ui.badgeSuccess,
  FAILED: ui.badgeDanger,
};

/**
 * Metadata only — filename, type, size, status. Deliberately never renders any part of a
 * document's contents: uploaded files are untrusted input (AGENTS.md rule 12), and inlining them
 * would be exactly the injection surface docs/SECURITY_AND_TESTING.md warns about.
 */
export function KnowledgeTable({ documents, canDelete, deleteAction }: KnowledgeTableProps) {
  const router = useRouter();
  const [refreshing, startRefresh] = useTransition();

  if (documents.length === 0) {
    return (
      <div className={ui.empty}>
        <p>No documents uploaded yet.</p>
      </div>
    );
  }

  return (
    <div className={ui.tableCard}>
      <div className={styles.tableToolbar}>
        <button
          type="button"
          className={ui.secondary}
          onClick={() => startRefresh(() => router.refresh())}
          disabled={refreshing}
        >
          {refreshing ? "Refreshing…" : "Refresh status"}
        </button>
      </div>

      <div className={ui.tableScroll}>
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
                <td className={styles.fileCell}>{document.filename}</td>
                <td className={styles.metaCell}>{document.mimeType}</td>
                <td className={styles.metaCell}>{formatSize(document.sizeBytes)}</td>
                <td>
                  <span className={STATUS_CLASS[document.status]}>{document.status}</span>
                  {document.status === "FAILED" && document.errorCode && (
                    <span role="alert" className={styles.errorCode}>
                      {document.errorCode}
                    </span>
                  )}
                </td>
                <td className={styles.timeCell}>{new Date(document.createdAt).toLocaleString()}</td>
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
      </div>
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
      <button type="submit" className={ui.danger}>
        Delete
      </button>
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
