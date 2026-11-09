import { redirect } from "next/navigation";
import { fetchMe } from "@/lib/api/me";
import { listKnowledgeDocuments } from "@/lib/api/knowledge";
import { deleteDocumentAction, uploadDocumentAction } from "./actions";
import { KnowledgeTable } from "./KnowledgeTable";
import { UploadDocumentDialog } from "./UploadDocumentDialog";

export const instant = false;

/**
 * Knowledge document list and upload (docs/TASKS.md T14). Documents sit at `QUEUED` until the
 * ingestion worker lands in T16 — this page shows that status honestly rather than implying the
 * document is searchable yet.
 *
 * Nothing here renders document *content*: only the metadata services/api returns. Uploaded files
 * are untrusted input, and no public URL to one exists anywhere in the product (the local adapter's
 * upload target is an authenticated endpoint, and the S3 adapter's is a short-lived presigned PUT).
 */
export default async function KnowledgePage({ params }: PageProps<"/app/[orgId]/knowledge">) {
  const { orgId } = await params;

  const me = await fetchMe();
  if (!me) {
    redirect("/sign-in");
  }
  const membership = me.organizations.find((org) => org.organizationId === orgId);
  if (!membership) {
    return (
      <main id="main-content" tabIndex={-1}>
        <h1>No access</h1>
        <p>You are not a member of this organization.</p>
      </main>
    );
  }

  // Display only — services/api re-checks both of these on every request (AGENTS.md rule 11).
  const canUpload = membership.role !== "VIEWER";
  const canDelete = membership.role === "ADMIN" || membership.role === "OWNER";

  const documents = await listKnowledgeDocuments(orgId);

  return (
    <div>
      <h1>Knowledge</h1>
      <p>
        Upload {".txt"}, {".md"} or {".pdf"} files up to 10 MiB. Uploaded documents stay at{" "}
        <strong>QUEUED</strong> until the ingestion worker indexes them.
      </p>

      {canUpload && <UploadDocumentDialog action={uploadDocumentAction.bind(null, orgId)} />}

      <KnowledgeTable
        documents={documents}
        canDelete={canDelete}
        deleteAction={deleteDocumentAction.bind(null, orgId)}
      />
    </div>
  );
}
