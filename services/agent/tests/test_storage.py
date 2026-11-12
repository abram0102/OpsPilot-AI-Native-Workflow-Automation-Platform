"""The indexer's object reader, including the containment check."""

from __future__ import annotations

from pathlib import Path

import pytest

from opspilot_agent.knowledge.storage import (
    LocalObjectStore,
    ObjectNotFoundError,
    StorageError,
)

# docs/TASKS.md T28: part of the security regression suite (`make test-security`) —
# object-key path escape.
pytestmark = pytest.mark.security


def test_reads_an_object_written_the_way_the_java_adapter_writes_it(tmp_path: Path) -> None:
    store = LocalObjectStore(tmp_path)
    key = "orgs/11111111-1111-1111-1111-111111111111/knowledge/abc"
    path = tmp_path / key
    path.parent.mkdir(parents=True)
    path.write_bytes(b"policy text")

    assert store.read(key) == b"policy text"
    assert store.exists(key)


def test_a_missing_object_is_distinguishable_from_an_unreadable_one(tmp_path: Path) -> None:
    store = LocalObjectStore(tmp_path)

    # A document deleted mid-ingest lands here, and the indexer records a different code for it
    # than for an I/O failure.
    with pytest.raises(ObjectNotFoundError):
        store.read("orgs/x/knowledge/missing")
    assert not store.exists("orgs/x/knowledge/missing")


@pytest.mark.parametrize(
    "key",
    [
        "../../../../etc/passwd",
        "orgs/../../../etc/passwd",
        "/etc/passwd",
    ],
)
def test_refuses_a_key_that_escapes_the_storage_root(tmp_path: Path, key: str) -> None:
    # Keys are server-generated, but this module reads them back out of a database row, so it
    # verifies containment rather than trusting that provenance.
    store = LocalObjectStore(tmp_path)

    with pytest.raises(StorageError):
        store.read(key)


def test_exists_reports_false_for_an_escaping_key_rather_than_raising(tmp_path: Path) -> None:
    assert not LocalObjectStore(tmp_path).exists("../../etc/passwd")
