"""Text extraction for the document types T13 accepts: TXT, MD, PDF.

Every input here is a file a customer uploaded, so this module treats its bytes
as hostile. docs/SECURITY_AND_TESTING.md requires "PDF sandbox/timeouts and
decompression guards"; concretely that means:

* a hard cap on extracted characters, so a decompression bomb cannot exhaust
  memory — a small PDF can expand to gigabytes of text;
* a hard cap on pages, so a file with millions of near-empty pages cannot spin;
* no network and no JavaScript: `pypdf` parses locally and executes nothing;
* every parser failure converted into `ExtractionError`, which carries a stable
  code the indexer records on the document.

A failure here is a normal outcome, not a crash. docs/DATA_MODEL.md allows
`FAILED -> QUEUED` retries, so the codes are chosen to tell a human whether
re-running could possibly help.
"""

from __future__ import annotations

import io
from enum import StrEnum

MAX_EXTRACTED_CHARS = 2_000_000
"""~2 MB of text. T13 caps uploads at 10 MiB, but compressed formats expand."""

MAX_PDF_PAGES = 2_000


class ExtractionErrorCode(StrEnum):
    UNSUPPORTED_TYPE = "EXTRACTION_UNSUPPORTED_TYPE"
    """The document's MIME type is not one this indexer can read."""

    MALFORMED_DOCUMENT = "EXTRACTION_MALFORMED_DOCUMENT"
    """The bytes are not a readable document of the declared type."""

    ENCRYPTED_DOCUMENT = "EXTRACTION_ENCRYPTED_DOCUMENT"
    """The PDF is password-protected; no amount of retrying will open it."""

    TOO_LARGE = "EXTRACTION_TOO_LARGE"
    """The document exceeds a guard limit (pages, or expanded text size)."""

    EMPTY_DOCUMENT = "EXTRACTION_EMPTY_DOCUMENT"
    """Parsed cleanly but yielded no text — e.g. a PDF of scanned images."""


class ExtractionError(Exception):
    def __init__(self, code: ExtractionErrorCode, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message

    def __str__(self) -> str:
        return f"[{self.code}] {self.message}"


def extract_text(content: bytes, mime_type: str) -> str:
    """Extracted plain text, or `ExtractionError` with a code the caller can store."""
    if mime_type in ("text/plain", "text/markdown"):
        text = _extract_plain_text(content)
    elif mime_type == "application/pdf":
        text = _extract_pdf_text(content)
    else:
        raise ExtractionError(
            ExtractionErrorCode.UNSUPPORTED_TYPE,
            f"No extractor for MIME type {mime_type!r}.",
        )

    text = text.strip()
    if not text:
        raise ExtractionError(
            ExtractionErrorCode.EMPTY_DOCUMENT,
            "The document contains no extractable text (a scanned PDF, perhaps).",
        )
    return text


def _extract_plain_text(content: bytes) -> str:
    _guard_size(len(content))
    # Uploaded text is not guaranteed to be valid UTF-8, and a stray byte is no reason to fail a
    # whole document — replace undecodable bytes rather than raising.
    return content.decode("utf-8", errors="replace")


def _extract_pdf_text(content: bytes) -> str:
    # Imported lazily so that TXT/MD ingestion does not pay for the PDF parser.
    from pypdf import PdfReader
    from pypdf.errors import PyPdfError

    try:
        reader = PdfReader(io.BytesIO(content))
    except PyPdfError as error:
        raise ExtractionError(
            ExtractionErrorCode.MALFORMED_DOCUMENT, "The PDF could not be parsed."
        ) from error
    except Exception as error:  # noqa: BLE001 - a hostile file can fail in library-specific ways
        raise ExtractionError(
            ExtractionErrorCode.MALFORMED_DOCUMENT,
            f"The PDF could not be read ({type(error).__name__}).",
        ) from error

    if reader.is_encrypted:
        # pypdf can sometimes open an empty-password PDF, but a document needing a real password
        # is terminal: retrying cannot help, so it gets its own code.
        raise ExtractionError(
            ExtractionErrorCode.ENCRYPTED_DOCUMENT, "The PDF is password-protected."
        )

    page_count = len(reader.pages)
    if page_count > MAX_PDF_PAGES:
        raise ExtractionError(
            ExtractionErrorCode.TOO_LARGE,
            f"The PDF has {page_count} pages, over the {MAX_PDF_PAGES}-page limit.",
        )

    parts: list[str] = []
    total = 0
    for page in reader.pages:
        try:
            page_text = page.extract_text() or ""
        except Exception as error:  # noqa: BLE001 - one bad page should not lose the document
            raise ExtractionError(
                ExtractionErrorCode.MALFORMED_DOCUMENT,
                f"A PDF page could not be read ({type(error).__name__}).",
            ) from error
        total += len(page_text)
        _guard_size(total)
        parts.append(page_text)

    return "\n".join(parts)


def _guard_size(size: int) -> None:
    if size > MAX_EXTRACTED_CHARS:
        raise ExtractionError(
            ExtractionErrorCode.TOO_LARGE,
            f"Extracted text exceeds the {MAX_EXTRACTED_CHARS}-character limit.",
        )
