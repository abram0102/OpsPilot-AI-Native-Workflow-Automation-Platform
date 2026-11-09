import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { KnowledgeTable } from "./KnowledgeTable";
import type { KnowledgeDocument } from "@/lib/api/knowledge";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ refresh: vi.fn() }),
}));

function document(overrides: Partial<KnowledgeDocument> = {}): KnowledgeDocument {
  return {
    id: "doc-1",
    filename: "refund-policy.md",
    mimeType: "text/markdown",
    sizeBytes: 2048,
    status: "QUEUED",
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
    ...overrides,
  };
}

const noopDelete = async () => {};

describe("KnowledgeTable", () => {
  it("shows an empty state when no documents have been uploaded", () => {
    render(<KnowledgeTable documents={[]} canDelete deleteAction={noopDelete} />);
    expect(screen.getByText("No documents uploaded yet.")).toBeInTheDocument();
  });

  it("renders each document's metadata, including its status badge", () => {
    render(
      <KnowledgeTable
        documents={[document({ filename: "handbook.pdf", sizeBytes: 1536 })]}
        canDelete={false}
        deleteAction={noopDelete}
      />,
    );

    expect(screen.getByText("handbook.pdf")).toBeInTheDocument();
    expect(screen.getByText("1.5 KiB")).toBeInTheDocument();
    expect(screen.getByText("QUEUED")).toBeInTheDocument();
  });

  it("never renders document contents — only the metadata the API returned", () => {
    const { container } = render(
      <KnowledgeTable
        documents={[document({ filename: "<img src=x onerror=alert(1)>.txt" })]}
        canDelete={false}
        deleteAction={noopDelete}
      />,
    );

    // The filename is untrusted input: it must land as text, never as markup.
    expect(container.querySelector("img")).toBeNull();
    expect(screen.getByText("<img src=x onerror=alert(1)>.txt")).toBeInTheDocument();
  });

  it("surfaces the error code on a failed document", () => {
    render(
      <KnowledgeTable
        documents={[document({ status: "FAILED", errorCode: "EXTRACTION_FAILED" })]}
        canDelete={false}
        deleteAction={noopDelete}
      />,
    );

    expect(screen.getByRole("alert")).toHaveTextContent("EXTRACTION_FAILED");
  });

  it("offers a delete control only to a caller the page says may delete", () => {
    const { rerender } = render(
      <KnowledgeTable documents={[document()]} canDelete={false} deleteAction={noopDelete} />,
    );
    expect(screen.queryByRole("button", { name: "Delete" })).not.toBeInTheDocument();

    rerender(<KnowledgeTable documents={[document()]} canDelete deleteAction={noopDelete} />);
    expect(screen.getByRole("button", { name: "Delete" })).toBeInTheDocument();
  });

  it("offers a refresh control, since documents stay QUEUED until the indexer runs", () => {
    render(<KnowledgeTable documents={[document()]} canDelete deleteAction={noopDelete} />);
    expect(screen.getByRole("button", { name: "Refresh status" })).toBeInTheDocument();
  });
});
