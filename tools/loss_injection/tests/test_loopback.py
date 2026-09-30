#!/usr/bin/env python3
"""Loopback functional test for loss_injection.py. Runnable standalone
(`python3 tools/loss_injection/tests/test_loopback.py`) or from CI. No test
framework dependency, so it can run in the CI job that doesn't set up
pytest — see .github/workflows/ci.yml's tools-smoke-test job.
"""
from __future__ import annotations

import socket
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from loss_injection import LossInjectingRelay  # noqa: E402


def run() -> None:
    listen = ("127.0.0.1", 0)
    forward_to_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    forward_to_sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1 << 20)
    forward_to_sock.bind(("127.0.0.1", 0))
    forward_to = forward_to_sock.getsockname()
    forward_to_sock.settimeout(2.0)

    relay = LossInjectingRelay(
        listen_addr=("127.0.0.1", 0),
        forward_to=forward_to,
        loss_fraction=0.2,
        reorder_fraction=0.0,
        delay_ms=0,
        jitter_ms=0,
        seed=42,
        log=open("/dev/null", "w"),
    )
    # Bind relay's listen socket ourselves first so we know the ephemeral port.
    relay._sock.bind(("127.0.0.1", 0))
    relay.listen_addr = relay._sock.getsockname()
    relay._sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1 << 20)

    def run_relay() -> None:
        relay._log_event(
            "relay_started",
            listen=f"{relay.listen_addr[0]}:{relay.listen_addr[1]}",
            forward_to=f"{relay.forward_to[0]}:{relay.forward_to[1]}",
            loss_fraction=relay.loss_fraction,
            reorder_fraction=relay.reorder_fraction,
        )
        while not relay._stop.is_set():
            try:
                data, _addr = relay._sock.recvfrom(65535)
            except OSError:
                break
            relay.packets_seen += 1
            if relay.rng.random() < relay.loss_fraction:
                relay.packets_dropped += 1
                continue
            relay._deliver(data)

    t = threading.Thread(target=run_relay, daemon=True)
    t.start()
    time.sleep(0.2)

    send_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    n = 200
    for i in range(n):
        send_sock.sendto(str(i).encode(), relay.listen_addr)
        time.sleep(0.001)

    received = 0
    try:
        while True:
            forward_to_sock.recvfrom(65535)
            received += 1
    except socket.timeout:
        pass

    relay.stop()

    assert relay.packets_seen >= n - 2, f"too many packets never reached relay: {relay.packets_seen}/{n}"
    loss_rate = relay.packets_dropped / relay.packets_seen
    assert 0.1 < loss_rate < 0.3, f"observed loss rate {loss_rate} outside expected range around 0.2"
    assert relay.packets_seen - relay.packets_dropped == received, (
        f"received ({received}) should equal seen-minus-dropped "
        f"({relay.packets_seen - relay.packets_dropped})"
    )
    print(f"PASS: loss_injection loopback test (seen={relay.packets_seen}, "
          f"dropped={relay.packets_dropped}, received={received}, loss_rate={loss_rate:.3f})")


if __name__ == "__main__":
    run()
