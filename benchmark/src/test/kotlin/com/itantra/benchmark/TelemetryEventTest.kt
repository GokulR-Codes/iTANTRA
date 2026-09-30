package com.itantra.benchmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TelemetryEventTest {

    @Test
    fun `nulls are encoded as JSON null, not omitted or zero`() {
        val e = TelemetryEvent(
            eventId = "evt-1", sentenceId = 1, deviceClass = "L", transport = null,
            monotonicTimestampMs = 1000
        )
        val json = e.toJsonLine()
        assertTrue(json.contains("\"transport\":null"))
        assertTrue(json.contains("\"sttRtf\":null"))
    }

    @Test
    fun `populated numeric fields are encoded without quotes`() {
        val e = TelemetryEvent(
            eventId = "evt-2", sentenceId = 2, deviceClass = "L", transport = "WIFI",
            sttRtf = 0.42, payloadBytes = 128, monotonicTimestampMs = 2000
        )
        val json = e.toJsonLine()
        assertTrue(json.contains("\"sttRtf\":0.42"))
        assertTrue(json.contains("\"payloadBytes\":128"))
        assertTrue(json.contains("\"transport\":\"WIFI\""))
    }

    @Test
    fun `string fields with quotes and backslashes are escaped`() {
        val e = TelemetryEvent(
            eventId = "evt with \"quotes\" and \\backslash", sentenceId = 3, deviceClass = "L",
            transport = null, monotonicTimestampMs = 3000
        )
        val json = e.toJsonLine()
        assertTrue(json.contains("evt with \\\"quotes\\\" and \\\\backslash"))
    }

    @Test
    fun `writer appends one JSON object per line`() {
        val sb = StringBuilder()
        val writer = JsonlTelemetryWriter(sb)
        writer.write(TelemetryEvent("a", 1, "L", "WIFI", monotonicTimestampMs = 1))
        writer.write(TelemetryEvent("b", 2, "M", "BLE", monotonicTimestampMs = 2))
        val lines = sb.toString().trim().split("\n")
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("\"eventId\":\"a\""))
        assertTrue(lines[1].contains("\"eventId\":\"b\""))
    }
}
