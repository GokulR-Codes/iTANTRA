"""
Reference implementation + honest bitrate measurement for §22/§35.

Mirrors the design of codec/src/main/kotlin/itantra/codec/{Tokenizer,
EntropyCoder,SemanticCodec}.kt so the algorithm is proven on real bytes
before being trusted in compiled form (same rationale as
protocol/validation/protocol_reference.py — no kotlinc/gradle in this
sandbox).

Pipeline under test: Unicode NFC normalization -> whitespace word
pre-tokenization -> byte-pair-encoding subword tokenization -> compact
integer IDs -> optional order-0 Huffman entropy stage.

Baselines compared, per §35: raw UTF-8 text bytes, and the semantic
(BPE) token stream with and without the entropy stage. An Opus-equivalent
AUDIO baseline is explicitly NOT measured here — see the "NOT MEASURED"
section at the bottom; it requires real recorded audio and libopus,
neither available in this offline sandbox, and is tracked in TODO.md.

Run: python3 bitrate_benchmark.py
"""
from __future__ import annotations

import heapq
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path


# ---------------------------------------------------------------------------
# 1. BPE tokenizer (language-agnostic training over the shared corpus, as a
#    first cut — the production Kotlin design in Tokenizer.kt supports
#    per-language vocabularies later if a shared one under-performs;
#    that comparison is future work, not claimed here).
# ---------------------------------------------------------------------------

END_MARKER = "\u0000"  # end-of-word marker, chosen outside any real script's range


def load_corpus(path: Path) -> list[tuple[str, str]]:
    rows = []
    with open(path, encoding="utf-8") as f:
        next(f)  # header
        for line in f:
            line = line.rstrip("\n")
            if not line:
                continue
            lang, sentence = line.split("\t", 1)
            rows.append((lang, unicodedata.normalize("NFC", sentence)))
    return rows


def word_symbols(word: str) -> tuple[str, ...]:
    return tuple(list(word) + [END_MARKER])


class BpeTokenizer:
    def __init__(self):
        self.merges: list[tuple[str, str]] = []
        self.vocab: dict[str, int] = {}
        self.unk_id: int = -1

    def train(self, sentences: list[str], num_merges: int = 80):
        word_freqs: Counter[tuple[str, ...]] = Counter()
        for s in sentences:
            for w in s.split():
                word_freqs[word_symbols(w)] += 1

        base_symbols = set()
        for w in word_freqs:
            base_symbols.update(w)
        vocab_symbols = sorted(base_symbols)  # deterministic ordering

        for _ in range(num_merges):
            pair_counts: Counter[tuple[str, str]] = Counter()
            for word, freq in word_freqs.items():
                for i in range(len(word) - 1):
                    pair_counts[(word[i], word[i + 1])] += freq
            if not pair_counts:
                break
            best_pair, best_count = pair_counts.most_common(1)[0]
            if best_count < 2:
                break  # no benefit merging a pair that occurs once
            merged_symbol = best_pair[0] + best_pair[1]
            self.merges.append(best_pair)
            vocab_symbols.append(merged_symbol)

            new_word_freqs: Counter[tuple[str, ...]] = Counter()
            for word, freq in word_freqs.items():
                new_word = []
                i = 0
                while i < len(word):
                    if i < len(word) - 1 and (word[i], word[i + 1]) == best_pair:
                        new_word.append(merged_symbol)
                        i += 2
                    else:
                        new_word.append(word[i])
                        i += 1
                new_word_freqs[tuple(new_word)] += freq
            word_freqs = new_word_freqs

        # Assign IDs: 0 reserved for UNK, then symbols in first-appearance
        # order. NOTE: this ordering is what the first benchmark run used,
        # and it's what exposed the English-regression finding in
        # RESULTS.md. Kept here (see `id_order` param below) so that
        # finding stays reproducible; the corrected assignment is
        # `id_order="frequency"`.
        self.vocab = {"<unk>": 0}
        for sym in vocab_symbols:
            if sym not in self.vocab:
                self.vocab[sym] = len(self.vocab)
        self.unk_id = self.vocab["<unk>"]
        self._word_freqs_final = word_freqs  # kept for frequency-based re-ID

    def reassign_ids_by_frequency(self, sentences: list[str]):
        """
        Fix for the discovery-order regression documented in RESULTS.md:
        re-assign integer IDs so the MOST FREQUENT symbols (across the
        actual training corpus, counted post-merge) get the LOWEST IDs.
        This is standard practice for BPE/subword vocabularies deployed
        in production and should keep single-byte varint IDs (<128) for
        whatever is common in ANY language, not just whichever language
        happened to dominate the raw training corpus by sentence count.
        Must be called after train(). Mutates self.vocab in place.
        """
        freq = Counter()
        for w, count in self._word_freqs_final.items():
            for sym in w:
                freq[sym] += count
        # Deterministic tie-break: by frequency desc, then symbol asc.
        ordered = sorted(freq.keys(), key=lambda s: (-freq[s], s))
        new_vocab = {"<unk>": 0}
        for sym in ordered:
            if sym not in new_vocab:
                new_vocab[sym] = len(new_vocab)
        # Any vocab symbol not seen in word_freqs (shouldn't happen, but
        # fail-safe) gets appended at the end rather than dropped.
        for sym in self.vocab:
            if sym not in new_vocab:
                new_vocab[sym] = len(new_vocab)
        self.vocab = new_vocab
        self.unk_id = self.vocab["<unk>"]

    def _apply_merges(self, word: tuple[str, ...]) -> list[str]:
        symbols = list(word)
        for a, b in self.merges:
            i = 0
            merged = []
            while i < len(symbols):
                if i < len(symbols) - 1 and symbols[i] == a and symbols[i + 1] == b:
                    merged.append(a + b)
                    i += 2
                else:
                    merged.append(symbols[i])
                    i += 1
            symbols = merged
        return symbols

    def encode(self, sentence: str) -> list[int]:
        ids = []
        for w in sentence.split():
            for sym in self._apply_merges(word_symbols(w)):
                ids.append(self.vocab.get(sym, self.unk_id))
        return ids

    def decode(self, ids: list[int]) -> str:
        rev = {v: k for k, v in self.vocab.items()}
        out = []
        for i in ids:
            sym = rev.get(i, "<unk>")
            out.append(sym)
        text = "".join(out).replace(END_MARKER, " ")
        return text.strip()


# ---------------------------------------------------------------------------
# 2. Compact integer encoding of a token ID stream: unsigned LEB128 varint
#    (matches what SemanticPacket.tokenIds would cost on the wire before
#    any further entropy coding).
# ---------------------------------------------------------------------------

def varint_encode(n: int) -> bytes:
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            break
    return bytes(out)


def varint_stream_bits(ids: list[int]) -> int:
    return sum(len(varint_encode(i)) * 8 for i in ids)


# ---------------------------------------------------------------------------
# 3. Optional order-0 static Huffman coder over the token ID alphabet
#    (§22's "optional entropy coding stage").
# ---------------------------------------------------------------------------

def huffman_code_lengths(freqs: dict[int, int]) -> dict[int, int]:
    if len(freqs) == 1:
        only = next(iter(freqs))
        return {only: 1}
    heap = [[f, [[sym, ""]]] for sym, f in freqs.items()]
    heapq.heapify(heap)
    counter = 0
    while len(heap) > 1:
        lo = heapq.heappop(heap)
        hi = heapq.heappop(heap)
        for pair in lo[1]:
            pair[1] = "0" + pair[1]
        for pair in hi[1]:
            pair[1] = "1" + pair[1]
        counter += 1
        heapq.heappush(heap, [lo[0] + hi[0], lo[1] + hi[1]])
    codes = {sym: len(code) for sym, code in heap[0][1]}
    return codes


def huffman_stream_bits(ids: list[int]) -> int:
    freqs = Counter(ids)
    lengths = huffman_code_lengths(dict(freqs))
    return sum(lengths[i] for i in ids)


# ---------------------------------------------------------------------------
# 4. Run the comparison
# ---------------------------------------------------------------------------

def main():
    corpus_path = Path(__file__).parent / "corpus" / "alert_sentences.tsv"
    rows = load_corpus(corpus_path)
    sentences = [s for _, s in rows]

    tokenizer = BpeTokenizer()
    tokenizer.train(sentences, num_merges=80)

    raw_utf8_bits_total = 0
    varint_bits_total = 0
    huffman_bits_total = 0
    all_ids: list[int] = []
    per_sentence = []

    for lang, sentence in rows:
        raw_bits = len(sentence.encode("utf-8")) * 8
        ids = tokenizer.encode(sentence)
        v_bits = varint_stream_bits(ids)
        all_ids.extend(ids)
        raw_utf8_bits_total += raw_bits
        varint_bits_total += v_bits
        per_sentence.append((lang, sentence, raw_bits, v_bits, ids))

        # sanity: lossless round trip
        decoded = tokenizer.decode(ids)
        assert decoded == sentence, f"LOSSY round trip: {sentence!r} -> {decoded!r}"

    # --- Corrected ID assignment (fixes the English regression) ---
    tokenizer.reassign_ids_by_frequency(sentences)
    varint_bits_fixed_total = 0
    all_ids_fixed: list[int] = []
    per_sentence_fixed = []
    for lang, sentence in rows:
        ids = tokenizer.encode(sentence)
        v_bits = varint_stream_bits(ids)
        varint_bits_fixed_total += v_bits
        all_ids_fixed.extend(ids)
        per_sentence_fixed.append((lang, sentence, v_bits))
        decoded = tokenizer.decode(ids)
        assert decoded == sentence, f"LOSSY round trip after re-ID: {sentence!r} -> {decoded!r}"
    huffman_bits_fixed_total = huffman_stream_bits(all_ids_fixed)

    # Huffman stage sized globally (shared code table across the session,
    # as a real deployment would ship one table per language pack).
    huffman_bits_total = huffman_stream_bits(all_ids)

    vocab_size = len(tokenizer.vocab)
    num_merges_learned = len(tokenizer.merges)

    print("=" * 70)
    print("SEMANTIC CODEC — BITRATE COMPARISON (placeholder corpus, §35)")
    print("=" * 70)
    print(f"Corpus: {len(rows)} sentences, {len(set(l for l, _ in rows))} languages")
    print(f"Learned vocab size: {vocab_size} (including <unk>)")
    print(f"BPE merges learned: {num_merges_learned}")
    print(f"Round-trip check: LOSSLESS for all {len(rows)} sentences (see assert above)")
    print()
    print(f"{'Representation':<32}{'Total bits':>12}{'Bytes':>10}{'vs raw UTF-8':>16}")
    print("-" * 70)

    def row(name, bits):
        pct = 100.0 * bits / raw_utf8_bits_total
        print(f"{name:<32}{bits:>12}{bits / 8:>10.1f}{pct:>15.1f}%")

    row("Raw UTF-8 text", raw_utf8_bits_total)
    row("Semantic tokens (varint, discovery-order IDs)", varint_bits_total)
    row("Semantic tokens + Huffman (discovery-order)", huffman_bits_total)
    row("Semantic tokens (varint, FREQUENCY-sorted IDs)", varint_bits_fixed_total)
    row("Semantic tokens + Huffman (frequency-sorted)", huffman_bits_fixed_total)

    print()
    print("Per-sentence detail (discovery-order IDs):")
    for lang, sentence, raw_bits, v_bits, ids in per_sentence:
        print(f"  [{lang}] {sentence!r}  utf8={raw_bits}b  tokens={len(ids)}  varint={v_bits}b")

    print()
    en_before = [v for l, s, raw, v, ids in per_sentence if l == "en"]
    en_after = [v for l, s, v in per_sentence_fixed if l == "en"]
    en_raw = [raw for l, s, raw, v, ids in per_sentence if l == "en"]
    print("English-only check (the sentences that regressed before the fix):")
    for (lang, sentence, raw_bits, _, _), before, after in zip(
        [row for row in per_sentence if row[0] == "en"], en_before, en_after
    ):
        verdict = "FIXED" if after <= raw_bits else "STILL WORSE THAN UTF-8"
        print(f"  {sentence!r}: utf8={raw_bits}b  before={before}b  after={after}b  [{verdict}]")

    print()
    print("=" * 70)
    print("NOT MEASURED IN THIS RUN (do not report these as tested):")
    print("=" * 70)
    print("- Opus-equivalent AUDIO baseline: requires real recorded speech")
    print("  + libopus; unavailable in this offline sandbox. §35 still")
    print("  requires it before any 'beats Opus' claim ships. TODO.md.")
    print("- Behavior under injected packet loss (that's protocol/'s")
    print("  RESULTS.md, not this file — this file is text-side only).")
    print("- Accuracy/meaning-preservation on a REAL corpus at scale —")
    print("  this is 50 hand-written sentences, not a speaker-disjoint")
    print("  Indic evaluation set. Gate 4/Gate 9 require the real one.")

    return {
        "raw_utf8_bits": raw_utf8_bits_total,
        "varint_bits": varint_bits_total,
        "huffman_bits": huffman_bits_total,
        "vocab_size": vocab_size,
    }


if __name__ == "__main__":
    main()
