-- Load-test fixture (docs/TASKS.md T34; docs/SECURITY_AND_TESTING.md §6's two tenant sizes:
-- "Org A 10k tickets / 1k knowledge chunks; Org B 1k tickets / 200 chunks").
--
-- Every row is fictional and generated. No production data belongs here, and nothing in this
-- file resembles a real person, order or address.
--
-- Idempotent: it deletes and recreates its own two organizations by slug, so re-running it gives
-- the same database every time. That is what makes a before/after comparison mean anything —
-- a benchmark against a different dataset measures the dataset.
--
--   psql -f benchmarks/fixture.sql
--
-- Two tenants on purpose, not one big one. A single-tenant benchmark cannot show whether the
-- tenant predicate is doing any work: with one organization in the table, every index lookup
-- "filters" to 100% of rows and a missing organization_id in an index costs nothing. Org B
-- exists so Org A's queries have something to exclude.

\set ON_ERROR_STOP on

BEGIN;

-- Clean slate for these two, leaving any other tenant (the demo seeder's, say) alone.
DELETE FROM organizations WHERE slug IN ('bench-alpha', 'bench-beta');

-- A user to own everything. Fictional subject, clearly marked.
INSERT INTO users (id, auth_subject, email, display_name)
VALUES (
    '00000000-0000-4000-8000-00000000b001',
    'bench|load-fixture',
    'load-fixture@bench.invalid',
    'Load Fixture (Benchmark)'
)
ON CONFLICT (auth_subject) DO NOTHING;

INSERT INTO organizations (id, name, slug, created_by)
VALUES
    ('00000000-0000-4000-8000-00000000a001', 'Bench Alpha', 'bench-alpha',
     '00000000-0000-4000-8000-00000000b001'),
    ('00000000-0000-4000-8000-00000000a002', 'Bench Beta', 'bench-beta',
     '00000000-0000-4000-8000-00000000b001');

INSERT INTO memberships (organization_id, user_id, role)
VALUES
    ('00000000-0000-4000-8000-00000000a001', '00000000-0000-4000-8000-00000000b001', 'OWNER'),
    ('00000000-0000-4000-8000-00000000a002', '00000000-0000-4000-8000-00000000b001', 'OWNER');

-- Customers: 200 for Alpha, 40 for Beta, so tickets spread across several rather than all
-- hanging off one row (which would make idx_tickets_customer unrealistically selective).
INSERT INTO customers (id, organization_id, display_name, email)
SELECT
    ('00000000-0000-4000-8001-' || lpad(n::text, 12, '0'))::uuid,
    '00000000-0000-4000-8000-00000000a001',
    'Alpha Customer ' || n,
    'alpha-' || n || '@bench.invalid'
FROM generate_series(1, 200) AS n;

INSERT INTO customers (id, organization_id, display_name, email)
SELECT
    ('00000000-0000-4000-8002-' || lpad(n::text, 12, '0'))::uuid,
    '00000000-0000-4000-8000-00000000a002',
    'Beta Customer ' || n,
    'beta-' || n || '@bench.invalid'
FROM generate_series(1, 40) AS n;

-- Org A: 10,000 tickets.
--
-- created_at is spread over 10,000 distinct minutes rather than all set to now(). Two reasons,
-- and the second is the one that matters: a realistic distribution, and — because the keyset
-- cursor is (created_at, id) — identical timestamps would collapse the whole pagination path
-- onto its tiebreaker branch and measure something the real system never does.
INSERT INTO tickets (
    organization_id, customer_id, subject, description, status, priority, created_by, created_at
)
SELECT
    '00000000-0000-4000-8000-00000000a001',
    ('00000000-0000-4000-8001-' || lpad((((n - 1) % 200) + 1)::text, 12, '0'))::uuid,
    'Bench ticket ' || n || ' — ' ||
        (ARRAY['delivery delayed', 'duplicate charge', 'cannot sign in',
               'wrong item received', 'refund not received'])[1 + (n % 5)],
    'Generated load-test content for ticket ' || n ||
        '. Fictional, repeatable, and deliberately long enough that row width is not trivial: '
        || repeat('the quick brown fox jumps over the lazy dog. ', 4),
    (ARRAY['OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'])[1 + (n % 4)],
    (ARRAY['LOW', 'MEDIUM', 'HIGH'])[1 + (n % 3)],
    '00000000-0000-4000-8000-00000000b001',
    now() - make_interval(mins => n)
FROM generate_series(1, 10000) AS n;

-- Org B: 1,000 tickets. Its job is to be in the way.
INSERT INTO tickets (
    organization_id, customer_id, subject, description, status, priority, created_by, created_at
)
SELECT
    '00000000-0000-4000-8000-00000000a002',
    ('00000000-0000-4000-8002-' || lpad((((n - 1) % 40) + 1)::text, 12, '0'))::uuid,
    'Beta ticket ' || n,
    'Generated load-test content for Beta ticket ' || n || '. Fictional.',
    (ARRAY['OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'])[1 + (n % 4)],
    (ARRAY['LOW', 'MEDIUM', 'HIGH'])[1 + (n % 3)],
    '00000000-0000-4000-8000-00000000b001',
    now() - make_interval(mins => n)
FROM generate_series(1, 1000) AS n;

COMMIT;

-- ANALYZE, not left to autovacuum. Without fresh statistics the planner is choosing from
-- estimates about a table it believes is empty, and the first EXPLAIN of a benchmark run would
-- measure the absence of statistics rather than the query.
ANALYZE customers;
ANALYZE tickets;

SELECT
    o.slug,
    count(t.id) AS tickets
FROM organizations o
LEFT JOIN tickets t ON t.organization_id = o.id
WHERE o.slug IN ('bench-alpha', 'bench-beta')
GROUP BY o.slug
ORDER BY o.slug;
