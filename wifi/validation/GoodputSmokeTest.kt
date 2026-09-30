// Standalone smoke test for the sliding-window goodput arithmetic added to
// WifiUdpTransport.currentGoodputBps() in this session (P4.5).
//
// WHY THIS IS A SEPARATE FILE, NOT A TEST AGAINST THE REAL CLASS:
// WifiUdpTransport.kt imports kotlinx.coroutines.flow.{Flow,MutableSharedFlow}, and
// kotlinx-coroutines-core is on Maven Central, which this sandbox's network allow-list
// blocks (confirmed this session — see the audit). So the real class cannot be compiled
// here at all, exactly as docs/PENDING_WORK_MASTER.md P0.2 already documented for its
// existing test suite. This file copies ONLY the new goodput logic verbatim (same field
// names, same arithmetic) into a coroutine-free harness so the arithmetic itself can
// actually be compiled and run with the real Kotlin 2.0.20 compiler in this session,
// rather than left completely unverified. It is NOT evidence that WifiUdpTransport.kt
// itself compiles or runs — see WIFI_COMPILE_REPRODUCTION.txt for that separate, still-
// negative result.

data class GoodputSample(val atMs: Long, val bytes: Int)

class GoodputAccountant(private val goodputWindowMs: Long = 5_000L) {
    private val goodputWindow = ArrayDeque<GoodputSample>()

    private fun pruneGoodputWindow(nowMs: Long) {
        while (goodputWindow.isNotEmpty() && nowMs - goodputWindow.first().atMs > goodputWindowMs) {
            goodputWindow.removeFirst()
        }
    }

    fun recordGoodputSample(bytes: Int, atMs: Long) {
        goodputWindow.addLast(GoodputSample(atMs, bytes))
        pruneGoodputWindow(atMs)
    }

    fun currentGoodputBps(nowMs: Long): Float {
        pruneGoodputWindow(nowMs)
        if (goodputWindow.isEmpty()) return 0f
        val totalBytes = goodputWindow.sumOf { it.bytes }
        val oldest = goodputWindow.first().atMs
        val elapsedMs = (nowMs - oldest).takeIf { it > 0 } ?: goodputWindowMs
        return (totalBytes.toDouble() * 8_000.0 / elapsedMs).toFloat()
    }
}

var checks = 0
var failures = 0

fun check(name: String, condition: Boolean) {
    checks++
    if (!condition) {
        failures++
        println("FAIL: $name")
    } else {
        println("PASS: $name")
    }
}

fun approxEquals(a: Float, b: Float, tol: Float = 0.5f) = kotlin.math.abs(a - b) <= tol

fun main() {
    // 1. Empty window -> 0 bps, not NaN/crash.
    run {
        val g = GoodputAccountant()
        check("empty window returns 0 bps", g.currentGoodputBps(1_000L) == 0f)
    }

    // 2. Single sample: 1000 bytes delivered "instantly" -> falls back to using the full
    //    window length as the denominator (documented behavior, avoids a divide-by-~0 spike).
    run {
        val g = GoodputAccountant(goodputWindowMs = 5_000L)
        g.recordGoodputSample(bytes = 1000, atMs = 10_000L)
        val bps = g.currentGoodputBps(10_000L)
        val expected = (1000 * 8_000.0 / 5_000.0).toFloat() // 1600 bps
        check("single-sample uses window length as denominator (~1600 bps)", approxEquals(bps, expected))
    }

    // 3. Steady 1000 bytes delivered once per second, at t=0,1,2,3,4,5s (6 samples, none
    //    pruned since none is more than 5000ms older than nowMs=5000) -> 6000 bytes over a
    //    5000ms span = 9600 bps.
    run {
        val g = GoodputAccountant(goodputWindowMs = 5_000L)
        for (t in 0..5) {
            g.recordGoodputSample(bytes = 1000, atMs = t * 1000L)
        }
        val bps = g.currentGoodputBps(5_000L)
        check("6 x 1000B samples over a 5000ms span is ~9600 bps", approxEquals(bps, 9600f, tol = 50f))
    }

    // 4. Old samples outside the window are pruned and stop counting.
    run {
        val g = GoodputAccountant(goodputWindowMs = 1_000L)
        g.recordGoodputSample(bytes = 5000, atMs = 0L)
        val bpsRightAfter = g.currentGoodputBps(500L)
        val bpsLongAfter = g.currentGoodputBps(10_000L) // 10s later, way outside the 1s window
        check("sample counts shortly after arrival", bpsRightAfter > 0f)
        check("sample is pruned once outside the window", bpsLongAfter == 0f)
    }

    // 5. Zero-byte payload doesn't crash or divide by zero.
    run {
        val g = GoodputAccountant()
        g.recordGoodputSample(bytes = 0, atMs = 1_000L)
        check("zero-byte sample handled without crashing", g.currentGoodputBps(1_000L) == 0f)
    }

    println()
    println("$checks checks run, ${checks - failures} passed, $failures failed")
    if (failures > 0) kotlin.system.exitProcess(1)
}
