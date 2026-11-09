"use client";

import { startTransition, useActionState, useState } from "react";
import { UPLOAD_ACCEPT_ATTRIBUTE, rejectionReasonFor } from "@/lib/api/knowledge-rules";
import type { UploadDocumentState } from "./actions";
import styles from "./UploadDocumentForm.module.css";

const initialState: UploadDocumentState = {};

export interface UploadDocumentFormProps {
  action: (prevState: UploadDocumentState, formData: FormData) => Promise<UploadDocumentState>;
  onUploaded?: () => void;
}

/**
 * The drop zone and its file rules (docs/TASKS.md T14).
 *
 * The chosen file lives in React state rather than in the `<input>`, and submitting builds the
 * FormData from that state. Dragging and picking then travel the same path — the alternative,
 * writing a dropped `FileList` back onto the input, needs a `DataTransfer` that only a real
 * browser has, which would have left the drop zone impossible to cover.
 *
 * The busy state is honest about what it knows: a Server Action submit reports no byte-level
 * upload progress, so this says "uploading" rather than drawing a percentage it cannot measure.
 */
export function UploadDocumentForm({ action, onUploaded }: UploadDocumentFormProps) {
  const [state, formAction, pending] = useActionState(action, initialState);
  const [selected, setSelected] = useState<File | null>(null);
  const [localError, setLocalError] = useState<string | null>(null);
  const [draggingOver, setDraggingOver] = useState(false);

  function offer(file: File | undefined) {
    if (!file) {
      return;
    }
    const rejection = rejectionReasonFor(file);
    setLocalError(rejection);
    setSelected(rejection ? null : file);
  }

  function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selected) {
      setLocalError("Choose a file to upload.");
      return;
    }
    const formData = new FormData();
    formData.append("file", selected);
    startTransition(() => formAction(formData));
    onUploaded?.();
  }

  return (
    <form onSubmit={handleSubmit}>
      <div
        className={`${styles.dropZone} ${draggingOver ? styles.dragging : ""}`}
        onDragOver={(event) => {
          event.preventDefault();
          setDraggingOver(true);
        }}
        onDragLeave={() => setDraggingOver(false)}
        onDrop={(event) => {
          event.preventDefault();
          setDraggingOver(false);
          offer(event.dataTransfer.files[0]);
        }}
      >
        <p>Drag a file here, or choose one below.</p>
        <label htmlFor="knowledge-file">File (.txt, .md or .pdf, up to 10 MiB)</label>
        <br />
        <input
          id="knowledge-file"
          type="file"
          name="file"
          accept={UPLOAD_ACCEPT_ATTRIBUTE}
          onChange={(event) => offer(event.target.files?.[0])}
        />
      </div>

      {selected && (
        <p>
          Selected: {selected.name} ({selected.size} bytes)
        </p>
      )}
      {localError && <p role="alert">{localError}</p>}
      {state.error && <p role="alert">{state.error}</p>}
      {state.uploadedFilename && (
        <p role="status">Uploaded {state.uploadedFilename}. It is queued for indexing.</p>
      )}

      <button type="submit" disabled={pending || Boolean(localError)}>
        {pending ? "Uploading…" : "Upload"}
      </button>
    </form>
  );
}
