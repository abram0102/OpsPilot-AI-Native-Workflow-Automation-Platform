# Failure drills — what was actually observed

Ticket **T33** asks to "drill killing worker after action DB commit, Redis outage, failed LLM and
RDS unavailable; **document outcomes, not guesses**."

This file records outcomes. Every number below was observed on a real stack on **2026-11-13**, not
reasoned about. Where a drill found something wrong, it says so and links the fix.

> **These drills ran against the local stack, not staging.** Staging does not exist — Gate H3 is
> not open. That matters for what these results do and do not prove, and
> [What these drills cannot tell you](#what-these-drills-cannot-tell-you) is explicit about it.

## How a dependency is made to fail

`scripts/drill-proxy.py` forwards a TCP port. The application is pointed at the proxy instead of
the real service, and killing the proxy severs every connection and refuses new ones — which is
what the application experiences when a managed service becomes unreachable.

The obvious alternative is to stop the real Redis or Postgres. That is the wrong way: they are a
developer's own services, shared with everything else they are doing, and **a drill that damages
the environment it rehearses on will not be run twice.**

```bash
python3 scripts/drill-proxy.py --listen 55999 --target 127.0.0.1:55432   # stand-in for the DB
python3 scripts/drill-proxy.py --listen 56999 --target 127.0.0.1:56379   # stand-in for Redis
# point the stack at 55999 / 56999, then kill the proxy to start the outage
```

---

## Drill 1 — Database unavailable

**Runbook:** §6 "Migration failure / DB outage". **Expected:** readiness DOWN, liveness UP, no
restart needed on recovery.

### First run: a real defect

| Observation | Value |
|---|---|
| Liveness during the outage | `UP` — correct |
| Readiness during the outage | **no response for 30 s**, then `DOWN` |
| Spring's own log | `Health contributor DataSourceHealthIndicator (db) took 30016ms to respond` |

Readiness was *right* and far too slow. Nothing overrode HikariCP's 30-second default
`connection-timeout`, so the health indicator sat waiting for a connection that would never come.

**Why that is a production problem, not a slow log line.** T31's ALB target-group health check has
a **5-second timeout**. Every probe would have timed out rather than receiving a clean `DOWN` — the
task gets pulled from rotation for the right reason by accident. Worse, each hanging probe holds a
Tomcat worker thread for the full 30 seconds; with the ALB and ECS both probing, those accumulate,
and a thread pool starved during a database outage takes **liveness** down with it. "Remove from
rotation" becomes "kill and restart", in a loop, exactly when the system is already degraded.

**Fix:** `spring.datasource.hikari.connection-timeout: 2000` (and `validation-timeout: 1000`) in
`application.yml`, so it applies to every profile.

The trade is deliberate and is written at the setting: this bound also governs how long a *request*
waits for a connection when the pool is busy, so under sustained load requests now fail after two
seconds instead of queueing for thirty. That is the better failure — fail fast, let the load
balancer shed — but it is a behaviour change, not a free win.

### Second run: after the fix

| Observation | Value |
|---|---|
| Readiness during the outage | `{"status":"DOWN"}` in **2,041 ms** |
| Liveness during the outage | `{"status":"UP"}` |
| Recovery | `UP` again **1 s** after the database returned, **no restart** |

2,041 ms is comfortably inside the ALB's 5-second timeout, so the load balancer now sees a real
`DOWN` instead of a timeout.

---

## Drill 2 — Redis outage

**Runbook:** §6 "Queue lag or Redis unavailable". **Expected:** writes keep working, the outbox
accrues, nothing is lost, the backlog drains on recovery.

| Observation | Value |
|---|---|
| Readiness during the outage | `UP`, answered in **29 ms** |
| Liveness | `UP` |
| A pending outbox row after ~8 s of outage | still `PENDING`, `attempts=2` |
| After Redis returned | `PUBLISHED` within **7 s**, `attempts=4` |
| Reached the stream | yes — `XLEN opspilot:events` increased |
| Manual intervention needed | none |

Readiness staying `UP` is the designed behaviour, not an oversight: Redis is deliberately outside
the readiness group because the outbox makes it non-critical for *accepting* writes. A node that
can still take a ticket should still receive traffic. `OutboxReadinessIntegrationTest` asserts the
same thing at the unit level; this confirms it on a running stack.

The rising `attempts` with a widening gap is the exponential backoff doing its job — the publisher
is not hot-looping against a dead dependency.

**Also observed:** the seeder (`local,seed`) ran to completion and **exited** during the Redis
outage. That is the T29/T32 fix holding — it used to hang forever on a task scheduler.

---

## Drill 3 — Worker killed mid-action

**Runbook:** §6 "Worker crash mid-action". **Expected:** the action takes effect **at most once**.

Sequence: start a run → wait for `AWAITING_APPROVAL` → approve → `kill -9` the worker immediately →
restart it.

| Point in time | Run status | Agent messages on the ticket |
|---|---|---|
| Before approving | `AWAITING_APPROVAL` | 0 |
| After approve, worker SIGKILLed | `AWAITING_APPROVAL` | **0** |
| 2 s after restarting the worker | `COMPLETED` | **1** |
| 15 s later (watching for a redelivery) | `COMPLETED` | **1** |

`tool_executions` held exactly one row: `PUBLISH_INTERNAL_REPLY SUCCEEDED`, keyed
`<runId>:reply`. The customer-visible effect happened once.

**What this drill does and does not cover.** The kill lands wherever it lands; it cannot
deterministically hit the window *between the action's database commit and the stream
acknowledgement*, which is the dangerous one. That exact window is covered deterministically by
`test_a_crash_after_the_action_but_before_the_ack_performs_it_once` and by
`AgentActionIdempotencyIntegrationTest`'s ten-concurrent-duplicates cases. This drill proves the
operational story around them: a worker can be killed outright, restarted, and the system converges
on one effect with no human touching the database.

---

## Drill 4 — LLM provider unreachable

**Runbook:** §6 "AI spend spike" is the related one; this is the failure case.
**Expected:** bounded retries, then a visible terminal failure with a code.

Worker started with `AI_MODE=real`, `LLM_BASE_URL=http://127.0.0.1:1/v1` (nothing listening),
`LLM_MAX_ATTEMPTS=2`, `LLM_TIMEOUT_SECONDS=3`.

| Observation | Value |
|---|---|
| Time to terminal state | **2 s** |
| Final status | `FAILED` |
| `last_error_code` | `AI_PROVIDER_ERROR` |
| Run `attempt_count` | `0` |

The run failed fast and visibly rather than hanging, which is the property that matters.

**One observation worth knowing before an incident:** `attempt_count` on the run reads `0` even
though the provider adapter did retry internally. They are different counters — the run's counts
how many times the run was *dispatched*, the adapter's counts network attempts. An operator reading
`FAILED attempts=0` could reasonably conclude nothing was ever tried. Not a defect, but a
documented trap; the provider's own retries are visible in the worker log, not on the run row.

---

## Bonus — poison message

Not a planned drill. A hand-inserted outbox event naming a run that does not exist was published,
and the worker **dead-lettered it on first delivery**:

```
Dead-lettered message 1794589312992-0 (reason=RUN_NOT_FOUND, deliveries=1)
```

`deliveries=1` is the part that matters: it did not retry a message that could never succeed.
That is §6's "poison message → DLQ or terminal failure with operator-visible reason; no endless
tight retry loop", observed rather than asserted.

---

## What these drills cannot tell you

They ran against the local stack. They prove how the **application** behaves when a dependency
disappears. They prove nothing about AWS, and the following remain untested:

- **RDS failover and snapshot restore.** `infra/aws/` configures automated backups with a 7-day
  retention and a mandatory final snapshot, and none of that has ever been exercised. A backup you
  have not restored is a backup you have not got.
- **CloudWatch alarms firing.** `docs/OBSERVABILITY.md` names thresholds; no alarm has ever fired,
  because no alarm exists yet.
- **ALB health-check behaviour.** Drill 1's fix was sized against the 5-second timeout configured
  in `infra/aws/modules/edge`, which has never run. The reasoning is sound and the number is
  unverified against a real target group.
- **ElastiCache failover**, which is not the same as the process-level outage drilled here.
- **Actual cloud costs.**

All of those are Gate H3 or H4.

## Backup and restore — written, never tested

The procedure, for when the gate opens. It is written from the AWS documentation and the
configuration in `infra/aws/`, and **no step of it has been executed.**

1. **Confirm what exists.** `aws rds describe-db-snapshots --db-instance-identifier opspilot-staging-postgres`.
   Automated snapshots are retained `db_backup_retention_days` (default 7); the final snapshot taken
   on delete is separate and is retained indefinitely.
2. **Restore to a new instance, never over the live one.**
   `aws rds restore-db-instance-from-db-snapshot --db-instance-identifier opspilot-staging-restore-<date> --db-snapshot-identifier <snapshot>`.
   Restoring creates a *new* instance; the original is untouched, which is what makes this safe to
   rehearse.
3. **Check the restored database before pointing anything at it**: `\dt`, the Flyway history
   (`select * from flyway_schema_history order by installed_rank desc limit 5`), and that
   `CREATE EXTENSION vector` is present — a restore that lost pgvector would fail at the first
   retrieval, not at restore time.
4. **Repoint the application** by updating the host in the `opspilot-staging/database` secret and
   rolling the services. Do not edit the task definition.
5. **Delete the restored instance** when the rehearsal is done, or it bills hourly forever.

**Point-in-time recovery** is available within the retention window and is the better tool for
"a migration went wrong twenty minutes ago":
`aws rds restore-db-instance-to-point-in-time --restore-time <iso8601>`.

Per `docs/DEPLOYMENT_RUNBOOK.md` §6, restoring is a human decision with business-impact review.
Nothing automates it, and nothing should.
