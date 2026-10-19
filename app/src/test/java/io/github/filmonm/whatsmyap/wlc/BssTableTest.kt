package io.github.filmonm.whatsmyap.wlc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BSSID -> AP from the captured (scrubbed) Cisco 'show ap summary' and Aruba 'show ap bss-table'. */
class BssTableTest {

    @Suppress("UNCHECKED_CAST")
    private val commands = Json.parse(javaClass.classLoader!!.getResource("wlc/commands.json")!!.readText()) as Map<String, Map<String, String>>

    @Test fun cisco() {
        val t = BssTable.cisco(commands.getValue("cisco").getValue("show ap summary"))
        assertEquals(85, t.aps)
        // the AP's own 5 GHz BSSIDs (show ap name 1001_AP07 wlan dot11 5ghz)
        val wlan = commands.getValue("cisco").getValue("show ap name 1001_AP07 wlan dot11 5ghz")
        val bssids = Regex("""[0-9a-f]{4}\.[0-9a-f]{4}\.[0-9a-f]{4}""").findAll(wlan).map { it.value }.toList()
        assertEquals(3, bssids.size)
        for (b in bssids) assertEquals(b, "1001_AP07", t.find(b)?.ap)
        assertEquals("BLDG 3, CLRM 4", t.find("c8:34:e5:27:fe:6f")?.location)
        // neighbours 0x20 apart don't bleed into each other
        assertEquals("1001_AP58", t.find("c834.e527.bacf")?.ap)
        assertEquals("1001_AP56", t.find("c834.e527.baef")?.ap)
        assertNull(t.find("00:11:22:33:44:55"))
        assertNull(t.find(""))
    }

    @Test fun aruba() {
        val text = commands.getValue("aruba").getValue("show ap bss-table")
        val t = BssTable.aruba(text)
        val rows = text.lines().filter { Regex("^[0-9a-f]{2}:").containsMatchIn(it) }
        assertEquals(1168, rows.size)
        for (line in rows) {
            val bss = line.substring(0, 17)
            val ap = line.trim().split(Regex("\\s+"))[8]
            assertEquals(bss, ap, t.find(bss)?.ap)
        }
        val hit = t.find("B4-5D-50-A8-9D-10")!!
        assertEquals("AP14", hit.ap); assertEquals("exact", hit.how); assertEquals("5", hit.band); assertEquals("EDNET", hit.ssid)
        val near = t.find("b4:5d:50:a8:9d:1f")!!                // not listed, same radio
        assertEquals("AP14", near.ap); assertEquals("radio", near.how)
        assertTrue(t.find("02:00:00:00:00:01") == null)
    }
}
