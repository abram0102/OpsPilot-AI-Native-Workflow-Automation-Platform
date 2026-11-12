"""Extraction treats every uploaded file as hostile input."""

from __future__ import annotations

import io

import pytest
from pypdf import PdfWriter

from opspilot_agent.knowledge.extraction import (
    MAX_EXTRACTED_CHARS,
    ExtractionError,
    ExtractionErrorCode,
    extract_text,
)

# docs/TASKS.md T28: part of the security regression suite (`make test-security`) —
# upload content guards (type, expansion, encryption).
pytestmark = pytest.mark.security


def make_pdf(pages: int = 1, text: str = "Hello from a PDF.") -> bytes:
    writer = PdfWriter()
    for _ in range(pages):
        page = writer.add_blank_page(width=200, height=200)
        page.merge_page(page)  # no-op merge keeps pypdf's page structure valid
    buffer = io.BytesIO()
    writer.write(buffer)
    return buffer.getvalue()


class TestPlainText:
    def test_extracts_utf8_text(self) -> None:
        assert extract_text(b"Refund policy: 30 days.", "text/plain") == ("Refund policy: 30 days.")

    def test_extracts_markdown(self) -> None:
        assert "Shipping" in extract_text(b"# Shipping\n\nTakes 3 days.", "text/markdown")

    def test_replaces_undecodable_bytes_rather_than_failing_the_document(self) -> None:
        # A stray non-UTF-8 byte is no reason to lose an otherwise readable document.
        result = extract_text(b"caf\xe9 policy", "text/plain")

        assert "policy" in result

    def test_rejects_text_over_the_expansion_guard(self) -> None:
        with pytest.raises(ExtractionError) as caught:
            extract_text(b"x" * (MAX_EXTRACTED_CHARS + 1), "text/plain")

        assert caught.value.code is ExtractionErrorCode.TOO_LARGE

    def test_rejects_a_document_with_no_text(self) -> None:
        with pytest.raises(ExtractionError) as caught:
            extract_text(b"   \n\n  ", "text/plain")

        assert caught.value.code is ExtractionErrorCode.EMPTY_DOCUMENT


class TestPdf:
    def test_rejects_a_broken_pdf_with_a_recoverable_code(self) -> None:
        # The acceptance criterion: "broken PDF -> FAILED and recoverable error code".
        with pytest.raises(ExtractionError) as caught:
            extract_text(b"%PDF-1.7\nthis is not actually a pdf", "application/pdf")

        assert caught.value.code is ExtractionErrorCode.MALFORMED_DOCUMENT

    def test_rejects_bytes_that_are_not_a_pdf_at_all(self) -> None:
        with pytest.raises(ExtractionError) as caught:
            extract_text(b"just some text", "application/pdf")

        assert caught.value.code is ExtractionErrorCode.MALFORMED_DOCUMENT

    def test_reports_an_image_only_pdf_as_empty_rather_than_succeeding_silently(self) -> None:
        # A scanned PDF parses fine but yields no text. Indexing it would create a READY document
        # that can never be retrieved, so it is a failure with its own code.
        with pytest.raises(ExtractionError) as caught:
            extract_text(make_pdf(), "application/pdf")

        assert caught.value.code is ExtractionErrorCode.EMPTY_DOCUMENT

    def test_rejects_an_encrypted_pdf_as_terminal(self) -> None:
        writer = PdfWriter()
        writer.add_blank_page(width=200, height=200)
        writer.encrypt("a-password")
        buffer = io.BytesIO()
        writer.write(buffer)

        with pytest.raises(ExtractionError) as caught:
            extract_text(buffer.getvalue(), "application/pdf")

        assert caught.value.code is ExtractionErrorCode.ENCRYPTED_DOCUMENT


class TestUnsupportedTypes:
    @pytest.mark.parametrize(
        "mime_type", ["application/zip", "image/png", "application/octet-stream", ""]
    )
    def test_refuses_a_type_it_cannot_read(self, mime_type: str) -> None:
        with pytest.raises(ExtractionError) as caught:
            extract_text(b"anything", mime_type)

        assert caught.value.code is ExtractionErrorCode.UNSUPPORTED_TYPE

    def test_error_renders_with_its_code_for_logs(self) -> None:
        error = ExtractionError(ExtractionErrorCode.TOO_LARGE, "too big")

        assert str(error) == "[EXTRACTION_TOO_LARGE] too big"
