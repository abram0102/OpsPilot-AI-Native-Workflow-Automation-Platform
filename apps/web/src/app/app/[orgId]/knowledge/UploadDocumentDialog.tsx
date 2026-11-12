"use client";

import { useRef } from "react";
import { UploadDocumentForm, type UploadDocumentFormProps } from "./UploadDocumentForm";
import ui from "@/styles/ui.module.css";

/**
 * Thin wrapper that puts {@link UploadDocumentForm} in a native `<dialog>` — focus trapping and
 * Escape-to-close for free, matching the create-ticket dialog. All the upload behaviour lives in
 * the form.
 */
export function UploadDocumentDialog({ action }: Pick<UploadDocumentFormProps, "action">) {
  const dialogRef = useRef<HTMLDialogElement>(null);

  return (
    <>
      <button type="button" onClick={() => dialogRef.current?.showModal()}>
        Upload document
      </button>
      <dialog ref={dialogRef} aria-labelledby="upload-document-heading">
        <h2 id="upload-document-heading">Upload document</h2>
        <UploadDocumentForm action={action} />
        <div className={ui.actionsEnd}>
          <button type="button" className={ui.secondary} onClick={() => dialogRef.current?.close()}>
            Close
          </button>
        </div>
      </dialog>
    </>
  );
}
