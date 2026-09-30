"""
Mirrors bluetooth/src/main/kotlin/itantra/bluetooth/BleTransport.kt's
pure-logic pieces (BleFragmenter, WindowedAckTracker) — the only parts of
that file that don't require real BLE hardware/Android SDK to exercise.

Run: python3 ble_logic_reference.py
"""
HEADER = 24
TRAILER = 2
ATT_HEADER = 3


def max_fragment_payload(negotiated_mtu, att_header=ATT_HEADER):
    usable_att = negotiated_mtu - att_header
    return usable_att - HEADER - TRAILER


def fragment(payload: bytes, negotiated_mtu: int):
    max_payload = max_fragment_payload(negotiated_mtu)
    if max_payload <= 0:
        raise ValueError(f"MTU {negotiated_mtu} leaves no room for payload")
    if not payload:
        return [b""]
    return [payload[i:i + max_payload] for i in range(0, len(payload), max_payload)]


class WindowedAckTracker:
    def __init__(self, window_size):
        self.window_size = window_size
        self.window_start = 0
        self.acked = set()

    def window_range(self):
        return range(self.window_start, self.window_start + self.window_size)

    def is_within_window(self, seq):
        return seq in self.window_range()

    def mark_acked(self, seq):
        if self.is_within_window(seq):
            self.acked.add(seq)

    def slide(self):
        while self.window_start in self.acked:
            self.acked.discard(self.window_start)
            self.window_start += 1

    def in_flight_count(self):
        return self.window_size - sum(1 for s in self.window_range() if s in self.acked)


def run_checks():
    results = []

    def check(name, cond, detail=""):
        results.append((name, bool(cond), detail))

    # --- Fragmentation math: DISCOVERED BUG, documented not hidden ---
    # At the BLE 4.0 spec floor ATT_MTU=23, usable payload after the
    # 3-byte ATT header is 20 bytes — LESS than our own §24 header alone
    # (24 bytes), before even counting the 2-byte trailer. This is a
    # real architectural finding, not a test artifact: it means iTantra's
    # current wire format cannot transmit ANYTHING over a BLE link that
    # never negotiates above the spec floor. Logged as DECISIONS.md entry
    # #5 and KNOWN_ISSUES.md. The correct behavior is to FAIL LOUDLY
    # (raise) rather than silently emit a corrupt/negative-length
    # fragment — confirm that's what happens:
    raised = False
    try:
        fragment(b"x", 23)
    except ValueError:
        raised = True
    check("MTU=23 (BLE 4.0 spec floor) correctly raises rather than "
          "silently producing a corrupt fragment (see DECISIONS.md #5)",
          raised)

    min_functional_mtu = ATT_HEADER + HEADER + TRAILER + 1  # smallest MTU that fits >=1 payload byte
    check(f"minimum functional MTU for this header format is {min_functional_mtu} "
          f"(i.e. >= this is required; BLE spec floor of 23 is NOT enough)",
          min_functional_mtu == 30, f"computed={min_functional_mtu}")

    check(f"MTU={min_functional_mtu} (minimum functional) yields exactly 1 byte of fragment payload",
          max_fragment_payload(min_functional_mtu) == 1, f"got {max_fragment_payload(min_functional_mtu)}")
    check("MTU=185 (realistic BLE 4.2+ negotiated value) yields far more fragment payload than the functional floor",
          max_fragment_payload(185) > max_fragment_payload(min_functional_mtu))

    msg = b"evacuate now, water rising near the shelter road, send help immediately please" * 3
    realistic_low_mtu = 32  # a real, working, if tight, negotiated MTU above the functional floor
    frags_small_mtu = fragment(msg, realistic_low_mtu)
    frags_big_mtu = fragment(msg, 185)
    reassembled_small = b"".join(frags_small_mtu)
    reassembled_big = b"".join(frags_big_mtu)
    check("fragmentation at a tight-but-functional MTU reassembles exactly", reassembled_small == msg,
          f"{len(frags_small_mtu)} fragments")
    check("fragmentation at large MTU reassembles exactly", reassembled_big == msg,
          f"{len(frags_big_mtu)} fragments")
    check("smaller functional MTU produces more (or equal) fragments than larger MTU",
          len(frags_small_mtu) >= len(frags_big_mtu),
          f"small={len(frags_small_mtu)} big={len(frags_big_mtu)}")
    check("fragment count stays within the 1-byte §24 field (<=255) for a realistic message",
          len(frags_small_mtu) <= 255)

    # --- Windowed ACK ---
    w = WindowedAckTracker(window_size=8)
    check("initial window starts at 0", w.window_start == 0)
    check("initial in-flight count equals window size", w.in_flight_count() == 8)

    w.mark_acked(0)
    w.mark_acked(1)
    w.slide()
    check("acking the first two in order slides the window forward by 2",
          w.window_start == 2, f"got {w.window_start}")

    w2 = WindowedAckTracker(window_size=4)
    w2.mark_acked(2)  # ack out of order — 0 and 1 still outstanding
    w2.slide()
    check("out-of-order ack does not slide the window past a gap",
          w2.window_start == 0, f"got {w2.window_start}")
    w2.mark_acked(0)
    w2.mark_acked(1)
    w2.slide()
    check("filling the gap slides the window all the way past the out-of-order ack too",
          w2.window_start == 3, f"got {w2.window_start}")

    w3 = WindowedAckTracker(window_size=4)
    w3.mark_acked(99)  # way outside window — must be ignored
    check("ack for a sequence number outside the window is ignored, not applied",
          w3.window_start == 0 and 99 not in w3.acked)

    return results


if __name__ == "__main__":
    results = run_checks()
    passed = sum(1 for _, ok, _ in results if ok)
    print(f"ble_logic_reference.py — {passed}/{len(results)} checks passed\n")
    for name, ok, detail in results:
        status = "PASS" if ok else "FAIL"
        line = f"[{status}] {name}"
        if detail:
            line += f"  ({detail})"
        print(line)
    if passed != len(results):
        raise SystemExit(1)
