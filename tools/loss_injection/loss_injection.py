#!/usr/bin/env python3
"""
loss_injection.py — a UDP relay that forwards packets between two
endpoints while injecting configurable, reproducible loss/reorder/delay.

Purpose (§33): before two real phones are available for field testing,
Division D needs a way to throw controlled channel degradation at the
protocol layer's ACK/FEC/adaptive-controller logic and get a deterministic,
repeatable result. This is a test tool, not a production transport — real
Wi-Fi/BLE transports live in wifi/ and bluetooth/.

Usage:
    python3 loss_injection.py --listen 127.0.0.1:9001 --forward-to 127.0.0.1:9002 \
        --loss 0.1 --reorder 0.02 --delay-ms 20 --jitter-ms 10 --seed 42

Every dropped/delayed/reordered packet is logged to stderr as a JSON line
so a test run's exact behavior is reproducible and auditable — this tool
must never silently vary its behavior between runs with the same --seed.
"""
from __future__ import annotations

import argparse
import json
import random
import socket
import sys
import threading
import time


def parse_addr(s: str) -> tuple[str, int]:
    host, port = s.rsplit(":", 1)
    return host, int(port)


class LossInjectingRelay:
    def __init__(
        self,
        listen_addr: tuple[str, int],
        forward_to: tuple[str, int],
        loss_fraction: float,
        reorder_fraction: float,
        delay_ms: float,
        jitter_ms: float,
        seed: int,
        log=sys.stderr,
    ) -> None:
        if not (0.0 <= loss_fraction <= 1.0):
            raise ValueError(f"loss_fraction must be in [0,1], got {loss_fraction}")
        if not (0.0 <= reorder_fraction <= 1.0):
            raise ValueError(f"reorder_fraction must be in [0,1], got {reorder_fraction}")
        if delay_ms < 0 or jitter_ms < 0:
            raise ValueError("delay_ms and jitter_ms must be non-negative")

        self.listen_addr = listen_addr
        self.forward_to = forward_to
        self.loss_fraction = loss_fraction
        self.reorder_fraction = reorder_fraction
        self.delay_ms = delay_ms
        self.jitter_ms = jitter_ms
        self.rng = random.Random(seed)
        self.log = log
        self.packets_seen = 0
        self.packets_dropped = 0
        self.packets_reordered = 0
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self._held_packet: bytes | None = None
        self._stop = threading.Event()

    def _log_event(self, event: str, **fields) -> None:
        record = {"event": event, "seq": self.packets_seen, **fields}
        print(json.dumps(record), file=self.log, flush=True)

    def _deliver(self, data: bytes) -> None:
        delay = max(0.0, self.delay_ms + self.rng.uniform(-self.jitter_ms, self.jitter_ms))
        if delay > 0:
            time.sleep(delay / 1000.0)
        self._sock.sendto(data, self.forward_to)

    def run(self) -> None:
        self._sock.bind(self.listen_addr)
        self._log_event(
            "relay_started",
            listen=f"{self.listen_addr[0]}:{self.listen_addr[1]}",
            forward_to=f"{self.forward_to[0]}:{self.forward_to[1]}",
            loss_fraction=self.loss_fraction,
            reorder_fraction=self.reorder_fraction,
        )
        while not self._stop.is_set():
            try:
                data, _addr = self._sock.recvfrom(65535)
            except OSError:
                break
            self.packets_seen += 1

            if self.rng.random() < self.loss_fraction:
                self.packets_dropped += 1
                self._log_event("dropped", size=len(data))
                continue

            if self._held_packet is not None:
                # Release the previously held packet after this one, achieving
                # a simple adjacent-pair reorder.
                current = data
                held = self._held_packet
                self._held_packet = None
                self.packets_reordered += 1
                self._log_event("reordered_pair", size_a=len(current), size_b=len(held))
                self._deliver(current)
                self._deliver(held)
                continue

            if self.rng.random() < self.reorder_fraction:
                self._held_packet = data
                continue

            self._deliver(data)

    def stop(self) -> None:
        self._stop.set()
        self._sock.close()
        self._log_event(
            "relay_stopped",
            packets_seen=self.packets_seen,
            packets_dropped=self.packets_dropped,
            packets_reordered=self.packets_reordered,
        )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--listen", required=True, help="host:port to receive packets on")
    parser.add_argument("--forward-to", required=True, help="host:port to forward surviving packets to")
    parser.add_argument("--loss", type=float, default=0.0, help="fraction of packets to drop, e.g. 0.1")
    parser.add_argument("--reorder", type=float, default=0.0, help="fraction of packets to hold-and-swap with the next one")
    parser.add_argument("--delay-ms", type=float, default=0.0, help="base one-way delay to add")
    parser.add_argument("--jitter-ms", type=float, default=0.0, help="+/- uniform jitter added to delay-ms")
    parser.add_argument("--seed", type=int, default=0, help="RNG seed, for reproducible test runs")
    args = parser.parse_args()

    relay = LossInjectingRelay(
        listen_addr=parse_addr(args.listen),
        forward_to=parse_addr(args.forward_to),
        loss_fraction=args.loss,
        reorder_fraction=args.reorder,
        delay_ms=args.delay_ms,
        jitter_ms=args.jitter_ms,
        seed=args.seed,
    )
    try:
        relay.run()
    except KeyboardInterrupt:
        relay.stop()


if __name__ == "__main__":
    main()
