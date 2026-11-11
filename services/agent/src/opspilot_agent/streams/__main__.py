"""The worker process: `python -m opspilot_agent.streams`.

Replaces the per-event CLIs T16 and T19 shipped as stand-ins. Those remain, because driving one
document or one run by hand is still the fastest way to debug either, but they are no longer the
only way events get consumed.

Shutdown is a signal, not an exception. SIGTERM (what a container runtime sends) and SIGINT (what
a terminal sends) both set the same event; the loop finishes the message it is holding and then
returns. A message that was *not* finished stays unacknowledged and is reclaimed by another
worker after the idle window, so an abrupt kill costs a redelivery rather than a lost job.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import signal
import sys

from opspilot_agent.config import get_settings
from opspilot_agent.streams.consumer import StreamConsumer, queue_health

log = logging.getLogger("opspilot_agent.streams")


async def _serve(once: bool) -> int:
    settings = get_settings()
    consumer = StreamConsumer(settings)
    stop = asyncio.Event()

    loop = asyncio.get_running_loop()
    for sig in (signal.SIGTERM, signal.SIGINT):
        # add_signal_handler rather than signal.signal: the latter runs the handler on an
        # arbitrary stack and cannot touch the event loop safely.
        loop.add_signal_handler(sig, stop.set)

    try:
        if once:
            # One pass, for an operator checking that the wiring works without leaving a process
            # behind. Still creates the group, still acknowledges properly.
            await consumer.ensure_group()
            processed = await consumer.process_once()
            print(json.dumps({"processed": processed, "metrics": vars(consumer.metrics)}))
            return 0

        log.info(
            "Consuming %s as %s in group %s.",
            settings.redis_stream_name,
            consumer.owner,
            settings.redis_consumer_group,
        )
        await consumer.run_forever(stop)
        return 0
    finally:
        await consumer.redis.aclose()


async def _report_health() -> int:
    settings = get_settings()
    consumer = StreamConsumer(settings)
    try:
        print(json.dumps(await queue_health(consumer.redis, settings), default=str))
        return 0
    finally:
        await consumer.redis.aclose()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="python -m opspilot_agent.streams",
        description="Consume opspilot:events and run the handler each event names.",
    )
    parser.add_argument(
        "--once",
        action="store_true",
        help="Process one batch and exit, instead of consuming until signalled.",
    )
    parser.add_argument(
        "--health",
        action="store_true",
        help="Print queue depth and backlog as JSON, then exit.",
    )
    args = parser.parse_args(argv)

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")

    if args.health:
        return asyncio.run(_report_health())
    return asyncio.run(_serve(args.once))


if __name__ == "__main__":
    sys.exit(main())
