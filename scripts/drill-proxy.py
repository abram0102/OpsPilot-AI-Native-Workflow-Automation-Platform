#!/usr/bin/env python3
"""A TCP forwarder that can be killed, for failure drills (docs/TASKS.md T33).

The drills in docs/FAILURE_DRILLS.md need a dependency to *go away* mid-flight: Redis during a
run, PostgreSQL while the API is serving. The obvious way is to stop the real service, and it is
the wrong way — those are a developer's own Redis and Postgres, shared with everything else they
are doing, and a drill that damages the environment it rehearses on will not be run twice.

So the application is pointed at this instead. Killing it severs every connection and refuses new
ones, which is what the application experiences when a managed service becomes unreachable:
`ECONNREFUSED` on connect, and a dropped socket on anything in flight.

    python3 scripts/drill-proxy.py --listen 56999 --target 127.0.0.1:56379

What it is not: a fault injector. It cannot add latency, corrupt bytes or fail a percentage of
connections. Those are different drills and would need a different tool; this one answers exactly
one question — what does the system do when a dependency disappears and comes back?

No third-party dependency on purpose: `socat` would do this in one line and is not installed
here, and asking an operator to install something before they can run a drill is how drills stop
being run.
"""

from __future__ import annotations

import argparse
import logging
import selectors
import socket
import sys
import threading

log = logging.getLogger("drill-proxy")

BUFFER_SIZE = 65536


def _pump(source: socket.socket, destination: socket.socket) -> None:
    """Copies one direction until either end closes."""
    try:
        while True:
            data = source.recv(BUFFER_SIZE)
            if not data:
                break
            destination.sendall(data)
    except OSError:
        # A reset or a half-closed socket is the normal end of a connection here, and during a
        # drill it is the expected one. Not worth a stack trace.
        pass
    finally:
        for sock in (source, destination):
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            sock.close()


def _handle(client: socket.socket, target_host: str, target_port: int) -> None:
    try:
        upstream = socket.create_connection((target_host, target_port), timeout=10)
    except OSError as error:
        log.warning("Upstream %s:%s refused the connection: %s", target_host, target_port, error)
        client.close()
        return

    # Both directions, each in its own thread. Daemon threads so a Ctrl-C or a kill takes the
    # whole process down immediately — a proxy that lingers after being "killed" would make a
    # drill report a recovery that never happened.
    for pair in ((client, upstream), (upstream, client)):
        threading.Thread(target=_pump, args=pair, daemon=True).start()


def serve(listen_port: int, target_host: str, target_port: int) -> int:
    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        listener.bind(("127.0.0.1", listen_port))
    except OSError as error:
        log.error("Could not bind 127.0.0.1:%s — %s", listen_port, error)
        return 1
    listener.listen(128)

    log.info(
        "Forwarding 127.0.0.1:%s -> %s:%s. Kill this process to simulate the outage.",
        listen_port,
        target_host,
        target_port,
    )

    selector = selectors.DefaultSelector()
    selector.register(listener, selectors.EVENT_READ)

    try:
        while True:
            for _key, _mask in selector.select(timeout=1):
                client, _address = listener.accept()
                _handle(client, target_host, target_port)
    except KeyboardInterrupt:
        log.info("Stopping.")
        return 0
    finally:
        listener.close()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="drill-proxy.py",
        description="Forward a TCP port so a drill can sever it without touching the real service.",
    )
    parser.add_argument("--listen", type=int, required=True, help="Local port to listen on.")
    parser.add_argument(
        "--target",
        required=True,
        help="host:port to forward to, e.g. 127.0.0.1:56379",
    )
    args = parser.parse_args(argv)

    host, _, port = args.target.rpartition(":")
    if not host or not port.isdigit():
        parser.error(f"--target must be host:port, got {args.target!r}")

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    return serve(args.listen, host, int(port))


if __name__ == "__main__":
    sys.exit(main())
