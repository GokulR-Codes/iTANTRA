package com.itantra.protocol

/**
 * CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF, no reflect, no final XOR).
 *
 * Chosen over a home-grown checksum per §30 ("standard, well-understood
 * primitives, no custom cryptography/parsing tricks") and because it is
 * cheap enough to run on every packet on a Class L device with no lookup
 * table required (bit-by-bit here; swap for a table if profiling on a
 * real Class L device shows it matters — measure first, per §31).
 */
object Crc16 {
    private const val POLY = 0x1021
    private const val INIT = 0xFFFF

    fun compute(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Int {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) {
            "CRC16.compute: bounds out of range (offset=$offset length=$length size=${bytes.size})"
        }
        var crc = INIT
        for (i in offset until offset + length) {
            crc = crc xor ((bytes[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) {
                    (crc shl 1) xor POLY
                } else {
                    crc shl 1
                }
                crc = crc and 0xFFFF
            }
        }
        return crc and 0xFFFF
    }
}
