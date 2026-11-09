# faq_corpus_v1

A small corpus of **fictional** FAQ documents for the knowledge indexer (ticket **T16**).

This is ingestion *source material*, not an evaluation set. It exists so the indexer and
tenant-scoped retrieval can be exercised against realistic prose — documents long enough to split
into several chunks, covering distinct topics so a query can be shown to retrieve the right
document's citation rather than any document at all.

It is deliberately **not** `support_triage_v1.jsonl`, the 60+ labelled triage cases that
`docs/SECURITY_AND_TESTING.md` section 5 requires and ticket **T30** builds. Those are tickets with
expected classifications and tool decisions, scored by a harness. These are just documents.

Everything here is invented: "Northwind Supply Co." is not a real company, and no policy, address,
order number or person named in these files refers to anything real.
