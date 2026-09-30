"""
Loopback fragmentation/reassembly test over a REAL OS UDP socket
(127.0.0.1), reusing the already-validated §24 packet format from
protocol_reference.py. This is a genuine (if Python, not yet Kotlin)
instance of Gate 5's acceptance criteria in spirit: "packetize -> send
over local socket -> reassemble, with CRC validated, on one device."

It does NOT satisfy Gate 5 itself (that requires the actual Kotlin
WifiTransport running on/against a real or emulated Android device) —
but it proves the underlying fragmentation scheme is sound over a real
kernel socket, not just in-process Python function calls, before that
Kotlin implementation is trusted to carry it.

Run: python3 wifi_loopback_reference.py
"""
from __future__ import annotations

import socket
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from protocol_reference import Packet, PacketFormatError, FLAG_FRAGMENTED, FLAG_IS_FINAL  # noqa: E402

MTU = 512  # conservative placeholder MTU for this test; real negotiation is §25/§26 scope


def fragment_message(message_id: int, session_id: int, sender_id: int,
                      lang_code: int, payload: bytes, mtu: int) -> list[bytes]:
    max_fragment_payload = mtu - 24 - 2  # header + trailer overhead per fragment
    assert max_fragment_payload > 0

    fragments = [payload[i:i + max_fragment_payload] for i in range(0, len(payload), max_fragment_payload)]
    if not fragments:
        fragments = [b""]
    count = len(fragments)
    assert count <= 255, "fragment count exceeds 1-byte field per §24"

    wire_packets = []
    for idx, frag_payload in enumerate(fragments):
        pkt = Packet(
            flags=FLAG_FRAGMENTED | (FLAG_IS_FINAL if idx == count - 1 else 0),
            session_id=session_id,
            sender_short_id=sender_id,
            message_id=message_id,
            sequence_number=idx,
            timestamp_ms=int(time.monotonic() * 1000) & 0xFFFFFFFF,
            lang_code=lang_code,
            message_type="FINAL",
            fragment_index=idx,
            fragment_count=count,
            payload=frag_payload,
        )
        wire_packets.append(pkt.encode())
    return wire_packets


def run_loopback_test(message: bytes, mtu: int = MTU) -> bytes:
    recv_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    recv_sock.bind(("127.0.0.1", 0))
    recv_sock.settimeout(2.0)
    recv_port = recv_sock.getsockname()[1]

    send_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

    wire_fragments = fragment_message(
        message_id=777, session_id=0xCAFEBABE & 0x7FFFFFFF, sender_id=0x1234,
        lang_code=1, payload=message, mtu=mtu,
    )

    for frag in wire_fragments:
        send_sock.sendto(frag, ("127.0.0.1", recv_port))

    received_by_index: dict[int, bytes] = {}
    expected_count = None
    deadline = time.monotonic() + 2.0
    while time.monotonic() < deadline and (expected_count is None or len(received_by_index) < expected_count):
        try:
            data, _ = recv_sock.recvfrom(65535)
        except socket.timeout:
            break
        try:
            pkt = Packet.decode(data)
        except PacketFormatError:
            continue  # corrupted fragment would be dropped in production too
        expected_count = pkt.fragment_count
        received_by_index[pkt.fragment_index] = pkt.payload

    send_sock.close()
    recv_sock.close()

    if expected_count is None or len(received_by_index) != expected_count:
        raise RuntimeError(
            f"reassembly incomplete: got {len(received_by_index)} of "
            f"{expected_count} fragments"
        )

    reassembled = b"".join(received_by_index[i] for i in range(expected_count))
    return reassembled


def run_checks():
    results = []

    def check(name, cond, detail=""):
        results.append((name, bool(cond), detail))

    # Small message, single fragment.
    small_msg = "help needed".encode("utf-8")
    out = run_loopback_test(small_msg)
    check("small single-fragment message survives real UDP loopback", out == small_msg,
          f"sent {len(small_msg)}B, got {len(out)}B")

    # Large message forcing multiple fragments at MTU=512.
    large_msg = ("evacuate now — water is rising near the riverbank shelter, " * 20).encode("utf-8")
    expected_fragments = -(-len(large_msg) // (MTU - 26))
    out2 = run_loopback_test(large_msg)
    check(f"large multi-fragment message ({expected_fragments} fragments) reassembles exactly",
          out2 == large_msg, f"sent {len(large_msg)}B, got {len(out2)}B")

    # Empty payload edge case.
    out3 = run_loopback_test(b"")
    check("empty-payload message round trips over real UDP loopback", out3 == b"")

    return results


if __name__ == "__main__":
    results = run_checks()
    passed = sum(1 for _, ok, _ in results if ok)
    print(f"wifi_loopback_reference.py — {passed}/{len(results)} checks passed\n")
    for name, ok, detail in results:
        status = "PASS" if ok else "FAIL"
        line = f"[{status}] {name}"
        if detail:
            line += f"  ({detail})"
        print(line)
    if passed != len(results):
        raise SystemExit(1)
