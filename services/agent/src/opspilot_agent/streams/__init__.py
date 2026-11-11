"""The Redis Streams consumer side of the transactional outbox (docs/TASKS.md T25).

T24 publishes envelopes onto `opspilot:events`; this package reads them with
`XREADGROUP`, reloads trusted state from the database, runs the matching
handler, and acknowledges only once that work is durable.
"""
