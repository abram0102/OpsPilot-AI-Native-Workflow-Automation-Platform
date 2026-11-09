"""Reading an uploaded document's bytes.

The spec does not say how the indexer obtains document content — T13 built
storage on the Java side only. This module is the decision recorded in
docs/DECISIONS.md ADR-040: the worker reads the *same* local storage root the
Java adapter writes to, using the `object_key` already stored on the document
row, and refuses any key that escapes that root.

Only the local adapter is implemented, matching the only adapter this
environment can exercise. S3 reading is deliberately absent rather than written
blind: it needs credentials and a bucket that do not exist here (Gate H3), and
an unverifiable second code path is worse than an explicit gap.
"""

from __future__ import annotations

from pathlib import Path


class StorageError(Exception):
    """The object could not be read."""


class ObjectNotFoundError(StorageError):
    """No stored object for that key — the document may have been deleted mid-ingest."""


class LocalObjectStore:
    """Reads objects from the directory the Java local adapter writes to."""

    def __init__(self, base_dir: str | Path) -> None:
        self._base_dir = Path(base_dir).expanduser().resolve()

    @property
    def base_dir(self) -> Path:
        return self._base_dir

    def read(self, object_key: str) -> bytes:
        path = self._safe_path(object_key)
        if not path.is_file():
            raise ObjectNotFoundError(f"No stored object for key {object_key!r}.")
        try:
            return path.read_bytes()
        except OSError as error:
            raise StorageError(f"Could not read object {object_key!r}.") from error

    def exists(self, object_key: str) -> bool:
        try:
            return self._safe_path(object_key).is_file()
        except StorageError:
            return False

    def _safe_path(self, object_key: str) -> Path:
        # Object keys are server-generated (T13/ADR-036), but this module is downstream of a
        # database row, so it verifies containment rather than trusting that provenance.
        candidate = (self._base_dir / object_key).resolve()
        if candidate != self._base_dir and self._base_dir not in candidate.parents:
            raise StorageError(f"Object key {object_key!r} escapes the storage root.")
        return candidate
