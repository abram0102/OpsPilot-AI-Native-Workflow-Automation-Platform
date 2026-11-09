import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { UploadDocumentForm } from "./UploadDocumentForm";
import type { UploadDocumentState } from "./actions";

function file(name: string, size: number): File {
  const created = new File(["x"], name);
  // `File.size` is read-only, and allocating 10 MiB of real bytes per test would be wasteful.
  Object.defineProperty(created, "size", { value: size });
  return created;
}

type UploadAction = (
  prevState: UploadDocumentState,
  formData: FormData,
) => Promise<UploadDocumentState>;

const noopAction = () => vi.fn<UploadAction>(async () => ({}));

function renderForm(action = noopAction()) {
  render(<UploadDocumentForm action={action} />);
  return {
    action,
    input: screen.getByLabelText(/^File \(/),
    dropZone: screen.getByText("Drag a file here, or choose one below."),
    upload: () => screen.getByRole("button", { name: /Upload/ }),
  };
}

describe("UploadDocumentForm", () => {
  it("rejects an unsupported file type before anything is sent", () => {
    const { action, input, upload } = renderForm();

    fireEvent.change(input, { target: { files: [file("payload.exe", 10)] } });

    expect(screen.getByRole("alert")).toHaveTextContent(
      "Unsupported file type. Allowed: .txt, .md, .pdf.",
    );
    expect(upload()).toBeDisabled();
    expect(action).not.toHaveBeenCalled();
  });

  it("rejects an oversized file before anything is sent", () => {
    const { action, input, upload } = renderForm();

    fireEvent.change(input, { target: { files: [file("huge.pdf", 11 * 1024 * 1024)] } });

    expect(screen.getByRole("alert")).toHaveTextContent("File exceeds the 10 MiB upload limit.");
    expect(upload()).toBeDisabled();
    expect(action).not.toHaveBeenCalled();
  });

  it("accepts a valid file and submits it to the action", async () => {
    const { action, input, upload } = renderForm();

    fireEvent.change(input, { target: { files: [file("refund-policy.md", 2048)] } });
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByText("Selected: refund-policy.md (2048 bytes)")).toBeInTheDocument();

    fireEvent.click(upload());

    await waitFor(() => expect(action).toHaveBeenCalledTimes(1));
    const submitted = action.mock.calls[0][1];
    expect((submitted.get("file") as File).name).toBe("refund-policy.md");
  });

  it("accepts a dropped file, the same as a picked one", () => {
    const { dropZone } = renderForm();

    fireEvent.drop(dropZone, { dataTransfer: { files: [file("dropped.txt", 64)] } });

    expect(screen.getByText("Selected: dropped.txt (64 bytes)")).toBeInTheDocument();
  });

  it("applies the same rules to a dropped file", () => {
    const { dropZone, upload } = renderForm();

    fireEvent.drop(dropZone, { dataTransfer: { files: [file("payload.exe", 64)] } });

    expect(screen.getByRole("alert")).toHaveTextContent("Unsupported file type.");
    expect(upload()).toBeDisabled();
  });

  it("clears a previous rejection once a valid file replaces it", () => {
    const { input, upload } = renderForm();

    fireEvent.change(input, { target: { files: [file("payload.exe", 10)] } });
    expect(screen.getByRole("alert")).toBeInTheDocument();

    fireEvent.change(input, { target: { files: [file("policy.txt", 100)] } });
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(upload()).toBeEnabled();
  });

  it("refuses to submit when no file has been chosen", () => {
    const { action, upload } = renderForm();

    fireEvent.click(upload());

    expect(screen.getByRole("alert")).toHaveTextContent("Choose a file to upload.");
    expect(action).not.toHaveBeenCalled();
  });

  it("surfaces an error the server action returned", async () => {
    const action = vi.fn(async (): Promise<UploadDocumentState> => ({
      error: "This file's content does not look like a valid PDF.",
    }));
    const { input, upload } = renderForm(action);

    fireEvent.change(input, { target: { files: [file("fake.pdf", 100)] } });
    fireEvent.click(upload());

    expect(
      await screen.findByText("This file's content does not look like a valid PDF."),
    ).toBeInTheDocument();
  });

  it("confirms the upload once the action reports success", async () => {
    const action = vi.fn(async (): Promise<UploadDocumentState> => ({
      uploadedFilename: "refund-policy.md",
    }));
    const { input, upload } = renderForm(action);

    fireEvent.change(input, { target: { files: [file("refund-policy.md", 100)] } });
    fireEvent.click(upload());

    expect(await screen.findByRole("status")).toHaveTextContent(
      "Uploaded refund-policy.md. It is queued for indexing.",
    );
  });
});
