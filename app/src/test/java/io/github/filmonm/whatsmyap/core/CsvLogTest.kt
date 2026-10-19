package io.github.filmonm.whatsmyap.core

import io.github.filmonm.whatsmyap.data.CsvLog
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CsvLogTest {
    @Test fun newColumnsWidenTheHeaderAndKeepOldRows() {
        val f = File(Files.createTempDirectory("csv").toFile(), "log.csv")
        CsvLog.write(f, listOf(mapOf("timestamp" to "2026-10-18 10:00:00", "module" to "fieldsurvey", "ssid" to "EDNET")))
        CsvLog.write(f, listOf(mapOf("timestamp" to "2026-10-18 10:05:00", "module" to "fieldsurvey",
            "photo_file" to "a,b.jpg", "speed_avg_mbps" to 152.345)))
        val rows = CsvLog.read(f)
        assertEquals(2, rows.size)
        assertEquals("EDNET", rows[0]["ssid"])
        assertEquals("", rows[0]["photo_file"])
        assertEquals("a,b.jpg", rows[1]["photo_file"])          // comma survives quoting
        assertEquals("152.3", rows[1]["speed_avg_mbps"])         // one decimal, like the prototype
        assertEquals("timestamp", CsvLog.parse(f.readText()).first().first())
    }

    @Test fun parsesQuotesAndNewlines() {
        val rows = CsvLog.parse("a,b\n\"x, \"\"y\"\"\",\"line1\nline2\"\n")
        assertEquals(listOf("x, \"y\"", "line1\nline2"), rows[1])
    }
}
