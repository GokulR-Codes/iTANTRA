package com.itantra.codec

import java.io.ByteArrayOutputStream
import java.util.PriorityQueue

/** Unsigned LEB128 varint encoding of a token ID stream. */
object VarintCodec {
    fun encode(ids: IntArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (id0 in ids) {
            var n = id0
            require(n >= 0) { "token ids must be non-negative, got $n" }
            while (true) {
                val b = n and 0x7F
                n = n ushr 7
                if (n != 0) {
                    out.write(b or 0x80)
                } else {
                    out.write(b)
                    break
                }
            }
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): IntArray {
        val ids = mutableListOf<Int>()
        var i = 0
        while (i < bytes.size) {
            var result = 0
            var shift = 0
            while (true) {
                if (i >= bytes.size) {
                    throw IllegalArgumentException("Truncated varint stream at index $i")
                }
                val b = bytes[i].toInt() and 0xFF
                i++
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                if (shift > 35) throw IllegalArgumentException("Varint too long (corrupt stream?)")
            }
            ids.add(result)
        }
        return ids.toIntArray()
    }
}

/**
 * Optional order-0 static Huffman entropy stage over a token ID stream,
 * §22 ("optional entropy coding stage"). A per-session or per-language
 * code table is a fixed, small cost relative to the savings on longer
 * utterances — must be measured per §31 before assuming it's worth the
 * CPU cycles on a Class L device, not assumed. Behind
 * [FeatureFlags.entropyCodingEnabled] so it can be disabled without
 * touching the rest of the pipeline if that measurement goes badly.
 */
object HuffmanCoder {
    data class CodeTable(val codeLengthBits: Map<Int, Int>, val codes: Map<Int, Pair<Int, Int>>)
    // codes: symbol -> (bit pattern as int, bit length)

    fun buildTable(ids: IntArray): CodeTable {
        val freq = ids.toList().groupingBy { it }.eachCount()
        if (freq.isEmpty()) return CodeTable(emptyMap(), emptyMap())
        if (freq.size == 1) {
            val only = freq.keys.first()
            return CodeTable(mapOf(only to 1), mapOf(only to (0 to 1)))
        }

        data class Node(val freq: Int, val symbol: Int?, val left: Node?, val right: Node?)

        val pq = PriorityQueue<Node>(compareBy { it.freq })
        freq.forEach { (sym, f) -> pq.add(Node(f, sym, null, null)) }
        while (pq.size > 1) {
            val a = pq.poll()
            val b = pq.poll()
            pq.add(Node(a.freq + b.freq, null, a, b))
        }
        val root = pq.poll()

        val lengths = mutableMapOf<Int, Int>()
        val codes = mutableMapOf<Int, Pair<Int, Int>>()
        fun walk(n: Node, depth: Int, code: Int) {
            if (n.symbol != null) {
                lengths[n.symbol] = maxOf(depth, 1)
                codes[n.symbol] = code to maxOf(depth, 1)
                return
            }
            n.left?.let { walk(it, depth + 1, code shl 1) }
            n.right?.let { walk(it, depth + 1, (code shl 1) or 1) }
        }
        walk(root, 0, 0)
        return CodeTable(lengths, codes)
    }

    /** Returns the encoded bit length only — used for bitrate measurement (§35) without needing a real bitstream writer yet. */
    fun encodedBitLength(ids: IntArray, table: CodeTable): Int =
        ids.sumOf { table.codeLengthBits[it] ?: error("symbol $it missing from code table") }
}
