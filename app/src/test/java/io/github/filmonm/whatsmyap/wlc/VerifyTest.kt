package io.github.filmonm.whatsmyap.wlc

import org.junit.Assert.assertEquals
import org.junit.Test

class VerifyTest {

    @Suppress("UNCHECKED_CAST")
    private val commands = Json.parse(javaClass.classLoader!!.getResource("wlc/commands.json")!!.readText()) as Map<String, Map<String, String>>

    private fun row(bssid: String, typed: String = "", ocr: String = "", photo: String = "", t: String = "2026-10-19 09:00:00") =
        mapOf("timestamp" to t, "bssid" to bssid, "ssid" to "EDNET", "label_entered" to typed, "ocr_number" to ocr,
            "ocr_label" to if (ocr.isEmpty()) "" else "AP-$ocr", "photo_file" to photo)

    @Test fun cisco() {
        val table = BssTable.cisco(commands.getValue("cisco").getValue("show ap summary"))
        val items = Verify.run(listOf(
            row("c8:34:e5:27:fe:6f", ocr = "7", photo = "a_AP7.jpg"),       // AP07, photo says 7
            row("c8:34:e5:27:fe:6e"),                                         // same AP, speed row without label
            row("bc:ab:f5:9a:36:cd", typed = "3"),                            // AP03, typed 3
            row("bc:ab:f5:9a:37:8f", ocr = "66", typed = "65"),              // AP65 radio, photo says 66
            row("bc:ab:f5:9a:3a:0d"),                                         // AP51, no label
            row("02:11:22:33:44:55", typed = "9"),                            // not this WLC
        ), table)
        val by = items.associate { (it.hit?.ap ?: it.bssid) to it.verdict }
        assertEquals("VERIFIED", by["1001_AP07"])
        assertEquals("CONFIRMED", by["1001_AP03"])
        assertEquals("MISMATCH", by["1001_AP65"])
        assertEquals("NO LABEL", by["1001_AP51"])
        assertEquals("NOT ON WLC", by["02:11:22:33:44:55"])
        assertEquals(5, items.size)                        // AP07's unlabelled row folds into its labelled one
        assertEquals("MISMATCH", items.first().verdict)    // problems first
        assertEquals(2, Verify.run(listOf(row("c8:34:e5:27:fe:6f", ocr = "7"), row("c8:34:e5:27:fe:6f", ocr = "7")), table).single().checks)
    }

    @Test fun aruba() {
        val table = BssTable.aruba(commands.getValue("aruba").getValue("show ap bss-table"))
        val items = Verify.run(listOf(row("b4:5d:50:a8:9d:10", typed = "14", ocr = "14")), table)
        assertEquals("VERIFIED", items.single().verdict)
        assertEquals("exact", items.single().hit?.how)
        val log = Verify.logRows("hb_ca", items, "of-samplelab-w1", "aruba8").single()
        assertEquals("AP14", log["ap_name"]); assertEquals("VERIFIED", log["verify_result"]); assertEquals("samplelab", log["site"])
    }
}
