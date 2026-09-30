"""
Python reference mirror of protocol/src/main/kotlin/itantra/protocol/*.kt

WHY THIS FILE EXISTS: this development session's sandbox has no network
access and no kotlinc/gradle/Android SDK installed (verified: `which
kotlinc gradle` returned nothing). Per Prime Directive #2 ("no fabricated
measurements"), the Kotlin unit tests in
protocol/src/test/kotlin/itantra/protocol/ are NOT claimed as passing —
they have not been run by any JVM test runner in this session. Instead,
this file byte-for-byte mirrors the CRC-16 algorithm, the §24 packet
layout, and the reliability-layer logic, and IS executed here, so at
least the algorithmic design is proven correct on real inputs before a
human (or a future session with Gradle available) ports it to a compiled,
JUnit-verified state.

Run: python3 protocol_reference.py
"""
from __future__ import annotations

import struct
from dataclasses import dataclass, field
from typing import Optional

MAGIC_NIBBLE = 0xA
CURRENT_VERSION = 1
HEADER_SIZE = 24
TRAILER_SIZE = 2

FLAG_FRAGMENTED = 0x01
FLAG_IS_FINAL = 0x02
FLAG_IS_ALERT = 0x04
FLAG_REQUIRES_ACK = 0x08

MESSAGE_TYPES = {
    "PARTIAL": 0x01, "FINAL": 0x02, "ALERT": 0x03, "RESIDUAL": 0x04,
    "ACK": 0x05, "PING": 0x06, "PAIR": 0x07,
}


def crc16_ccitt_false(data: bytes) -> int:
    crc = 0xFFFF
    for b in data:
        crc ^= (b << 8)
        for _ in range(8):
            if crc & 0x8000:
                crc = ((crc << 1) ^ 0x1021) & 0xFFFF
            else:
                crc = (crc << 1) & 0xFFFF
    return crc


class PacketFormatError(Exception):
    pass


@dataclass
class Packet:
    flags: int
    session_id: int
    sender_short_id: int
    message_id: int
    sequence_number: int
    timestamp_ms: int
    lang_code: int
    message_type: str
    fragment_index: int
    fragment_count: int
    payload: bytes
    version: int = CURRENT_VERSION

    def encode(self) -> bytes:
        magic_version = (MAGIC_NIBBLE << 4) | (self.version & 0x0F)
        header = struct.pack(
            ">BBIIHHIBBBBH",
            magic_version,
            self.flags & 0xFF,
            self.session_id & 0xFFFFFFFF,
            self.sender_short_id & 0xFFFFFFFF,
            self.message_id & 0xFFFF,
            self.sequence_number & 0xFFFF,
            self.timestamp_ms & 0xFFFFFFFF,
            self.lang_code & 0xFF,
            MESSAGE_TYPES[self.message_type],
            self.fragment_index & 0xFF,
            self.fragment_count & 0xFF,
            len(self.payload) & 0xFFFF,
        )
        assert len(header) == HEADER_SIZE, f"header size drifted: {len(header)}"
        body = header + self.payload
        crc = crc16_ccitt_false(body)
        return body + struct.pack(">H", crc)

    @staticmethod
    def decode(raw: bytes) -> "Packet":
        if len(raw) < HEADER_SIZE + TRAILER_SIZE:
            raise PacketFormatError(f"truncated: {len(raw)} bytes")
        (magic_version, flags, session_id, sender_short_id, message_id,
         sequence_number, timestamp_ms, lang_code, msg_type_code,
         fragment_index, fragment_count, payload_length) = struct.unpack(
            ">BBIIHHIBBBBH", raw[:HEADER_SIZE]
        )
        magic = (magic_version >> 4) & 0x0F
        version = magic_version & 0x0F
        if magic != MAGIC_NIBBLE:
            raise PacketFormatError(f"bad magic: {magic}")
        if version != CURRENT_VERSION:
            raise PacketFormatError(f"unsupported version: {version}")

        expected_total = HEADER_SIZE + payload_length + TRAILER_SIZE
        if len(raw) != expected_total:
            raise PacketFormatError(
                f"length mismatch: payloadLength={payload_length} implies "
                f"total={expected_total}, actual={len(raw)}"
            )

        payload = raw[HEADER_SIZE:HEADER_SIZE + payload_length]
        (crc_received,) = struct.unpack(">H", raw[HEADER_SIZE + payload_length:])
        crc_computed = crc16_ccitt_false(raw[:HEADER_SIZE + payload_length])
        if crc_received != crc_computed:
            raise PacketFormatError(
                f"CRC mismatch: received=0x{crc_received:04x} computed=0x{crc_computed:04x}"
            )

        msg_type = next(k for k, v in MESSAGE_TYPES.items() if v == msg_type_code)
        return Packet(
            flags=flags, session_id=session_id, sender_short_id=sender_short_id,
            message_id=message_id, sequence_number=sequence_number,
            timestamp_ms=timestamp_ms, lang_code=lang_code, message_type=msg_type,
            fragment_index=fragment_index, fragment_count=fragment_count,
            payload=payload, version=version,
        )


class ReplayWindow:
    def __init__(self, window_size: int = 64):
        self.window_size = window_size
        self.highest_seq: Optional[int] = None
        self.seen: list[bool] = []

    def accept(self, seq: int) -> bool:
        if self.highest_seq is None:
            self.highest_seq = seq
            self.seen = [True]
            return True
        diff = seq - self.highest_seq
        if diff > 0:
            self.seen = [True] + [False] * (diff - 1) + self.seen
            self.seen = self.seen[: self.window_size]
            self.highest_seq = seq
            return True
        if diff == 0:
            return False
        if -diff >= self.window_size:
            return False
        idx = -diff
        while len(self.seen) <= idx:
            self.seen.append(False)
        if self.seen[idx]:
            return False
        self.seen[idx] = True
        return True


def run_checks() -> list[tuple[str, bool, str]]:
    results = []

    def check(name, condition, detail=""):
        results.append((name, bool(condition), detail))

    # --- CRC-16/CCITT-FALSE known test vectors ---
    check("crc16: empty input == 0xFFFF (init value)",
          crc16_ccitt_false(b"") == 0xFFFF)
    check("crc16: '123456789' == 0x29B1 (standard check value)",
          crc16_ccitt_false(b"123456789") == 0x29B1,
          f"got 0x{crc16_ccitt_false(b'123456789'):04X}")

    # --- Header size resolves the blueprint's 24 vs 26 byte inconsistency ---
    # timestamp_ms MUST be session-relative monotonic ms (e.g. Android's
    # SystemClock.elapsedRealtime() minus a session-start baseline), NOT
    # Unix epoch millis: the wire field is only 4 bytes, so it can hold at
    # most ~49.7 days of milliseconds before wrapping. This was caught by
    # this reference script on first run (an epoch timestamp truncated
    # silently) — see DECISIONS.md and the doc comment on Packet.timestampMs
    # in PacketFormat.kt for the resolution.
    p = Packet(
        flags=FLAG_IS_FINAL | FLAG_REQUIRES_ACK, session_id=0x11223344,
        sender_short_id=0x55667788, message_id=42, sequence_number=7,
        timestamp_ms=1_234_567, lang_code=1, message_type="FINAL",
        fragment_index=0, fragment_count=1, payload=b"help fire evacuate",
    )
    encoded = p.encode()
    check("header is exactly 24 bytes", HEADER_SIZE == 24)
    check("encoded size == header(24) + payload + trailer(2)",
          len(encoded) == HEADER_SIZE + len(p.payload) + TRAILER_SIZE,
          f"encoded={len(encoded)} expected={HEADER_SIZE + len(p.payload) + TRAILER_SIZE}")

    # --- Round trip ---
    decoded = Packet.decode(encoded)
    check("round trip preserves all fields", decoded == p)

    # --- Document (not hide) the 32-bit timestamp wraparound explicitly ---
    epoch_ts = 1_735_000_000_000  # a realistic Unix-epoch ms value
    p_epoch = Packet(**{**p.__dict__, "timestamp_ms": epoch_ts})
    decoded_epoch = Packet.decode(p_epoch.encode())
    check(
        "epoch-scale timestamps truncate to low 32 bits on the wire "
        "(expected — this is why timestampMs must be session-relative, not epoch)",
        decoded_epoch.timestamp_ms == (epoch_ts & 0xFFFFFFFF),
        f"sent={epoch_ts} received={decoded_epoch.timestamp_ms}",
    )

    # --- Empty payload round trip ---
    p_empty = Packet(**{**p.__dict__, "payload": b""})
    check("empty payload round trips", Packet.decode(p_empty.encode()) == p_empty)

    # --- Corruption is rejected (fail closed, §30) ---
    corrupted = bytearray(encoded)
    corrupted[HEADER_SIZE] ^= 0x01
    try:
        Packet.decode(bytes(corrupted))
        check("corrupted payload byte is rejected", False)
    except PacketFormatError:
        check("corrupted payload byte is rejected", True)

    truncated = encoded[:-5]
    try:
        Packet.decode(truncated)
        check("truncated packet is rejected", False)
    except PacketFormatError:
        check("truncated packet is rejected", True)

    bad_magic = bytearray(encoded)
    bad_magic[0] = 0x00
    try:
        Packet.decode(bytes(bad_magic))
        check("bad magic nibble is rejected", False)
    except PacketFormatError:
        check("bad magic nibble is rejected", True)

    # --- Replay window ---
    w = ReplayWindow(window_size=8)
    check("replay window: increasing sequence all accepted",
          all(w.accept(i) for i in range(11)))
    w2 = ReplayWindow(window_size=8)
    w2.accept(5)
    check("replay window: exact duplicate rejected", w2.accept(5) is False)
    w3 = ReplayWindow(window_size=4)
    w3.accept(100)
    check("replay window: far-old sequence rejected", w3.accept(90) is False)
    w4 = ReplayWindow(window_size=8)
    w4.accept(10)
    w4.accept(12)
    late_accept = w4.accept(11)
    late_dup = w4.accept(11)
    check("replay window: late in-window packet accepted once, then rejected",
          late_accept is True and late_dup is False)

    # --- Packet-loss / reordering simulation over the wire format itself ---
    import random
    random.seed(1234)  # reproducible per §36's "seeded RNG" requirement
    for loss_pct in (0, 5, 10, 20):
        sent = []
        for seq in range(500):
            pkt = Packet(
                flags=FLAG_IS_FINAL, session_id=1, sender_short_id=1,
                message_id=seq, sequence_number=seq % 65536,
                timestamp_ms=seq, lang_code=1, message_type="FINAL",
                fragment_index=0, fragment_count=1,
                payload=f"msg-{seq}".encode(),
            )
            sent.append(pkt.encode())
        # simulate loss
        received_wire = [w for w in sent if random.random() * 100 >= loss_pct]
        rw = ReplayWindow(window_size=64)
        delivered = 0
        for wire in received_wire:
            try:
                pkt = Packet.decode(wire)
            except PacketFormatError:
                continue
            if rw.accept(pkt.sequence_number):
                delivered += 1
        expected_min = len(received_wire)  # no reordering injected here, so all should land
        check(
            f"loss_pct={loss_pct}: all non-dropped, uncorrupted packets delivered exactly once",
            delivered == expected_min,
            f"sent=500 arrived_at_wire={len(received_wire)} delivered={delivered}",
        )

    return results


if __name__ == "__main__":
    results = run_checks()
    passed = sum(1 for _, ok, _ in results if ok)
    print(f"protocol_reference.py — {passed}/{len(results)} checks passed\n")
    for name, ok, detail in results:
        status = "PASS" if ok else "FAIL"
        line = f"[{status}] {name}"
        if detail:
            line += f"  ({detail})"
        print(line)
    if passed != len(results):
        raise SystemExit(1)
