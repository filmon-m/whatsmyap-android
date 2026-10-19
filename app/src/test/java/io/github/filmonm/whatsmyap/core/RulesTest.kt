package io.github.filmonm.whatsmyap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelsTest {
    @Test fun parsesCommonLabelForms() {
        val r = Labels.parse("AP-O8 aP14 1001_AP026 APOLLO MAP-5 AP 3").map { it.number }
        assertEquals(listOf(8, 14, 26, 3), r)
    }

    @Test fun readsThePrintedTestLabel() {
        // what ML Kit / Tesseract returned for the user's AP-017 photo
        assertEquals(17, Labels.parse("AP-017").single().number)
        assertEquals(17, Labels.parse("AP-O17").single().number)
    }

    @Test fun ignoresTextThatIsNotAnApLabel() {
        assertTrue(Labels.parse("CISCO Aironet LAPTOP MAP 12").isEmpty())
    }

    @Test fun apNumberFromWlcNames() {
        assertEquals(14, Labels.apNumber("1002_AP14"))
        assertEquals(8, Labels.apNumber("AP008"))
        assertEquals(26, Labels.apNumber("1001_AP26"))
        assertNull(Labels.apNumber(""))
    }

    @Test fun verdicts() {
        assertEquals(Labels.Verdict.VERIFIED, Labels.verdict(14, null, 14))
        assertEquals(Labels.Verdict.MISMATCH, Labels.verdict(8, 8, 17))   // vendor typed what the WLC said
        assertEquals(Labels.Verdict.CONFIRMED, Labels.verdict(5, 5, null))
        assertEquals(Labels.Verdict.CONFLICT, Labels.verdict(null, 14, 41))
        assertEquals(Labels.Verdict.CONSISTENT, Labels.verdict(null, 14, 14))
        assertNull(Labels.verdict(14, null, null))
    }
}

class RulesTest {
    @Test fun bandsAndChannels() {
        assertEquals(Rules.BandChannel("2.4", 1), Rules.bandChannel(2412))
        assertEquals(Rules.BandChannel("2.4", 14), Rules.bandChannel(2484))
        assertEquals(Rules.BandChannel("5", 108), Rules.bandChannel(5540))
        assertEquals(Rules.BandChannel("6", 37), Rules.bandChannel(6135))
        assertEquals("", Rules.bandChannel(null).band)
    }

    @Test fun speedStatusPerBand() {
        assertEquals(Rules.Status.PASS, Rules.speedStatus(120.0, 150.0, "5"))
        assertEquals(Rules.Status.WARN, Rules.speedStatus(80.0, 150.0, "5"))
        assertEquals(Rules.Status.FAIL, Rules.speedStatus(20.0, 90.0, "6"))
        assertEquals(Rules.Status.PASS, Rules.speedStatus(30.0, 40.0, "2.4"))
        assertNull(Rules.speedStatus(30.0, 40.0, ""))
    }

    @Test fun teamsCheck() {
        assertTrue(Rules.teams(22.0, 4.0)!!.pass)
        assertEquals("jitter 66 > 30 ms", Rules.teams(40.0, 66.0)!!.why)
        assertEquals("RTT 120 > 100 ms, loss 2.5% > 1%", Rules.teams(120.0, 4.0, 2.5)!!.why)
        assertTrue(Rules.teams(40.0, 4.0, 0.4)!!.pass)
        assertNull(Rules.teams(null, 4.0))
    }

    @Test fun latencyMedianAndJitter() {
        val (median, jitter) = Rules.latencyStats(listOf(10.0, 14.0, 12.0))!!
        assertEquals(12.0, median, 0.001)
        assertEquals(3.0, jitter, 0.001)
    }

    @Test fun siteNames() {
        assertEquals("vultr-lax", Rules.siteName("http://lax-ca-us-ping.vultr.com/vultr.com.1000MB.bin"))
        assertEquals("vultr-wa", Rules.siteName("http://wa-us-ping.vultr.com/x.bin"))
        assertEquals("192.0.2.3", Rules.siteName("http://192.0.2.3:8080/file"))
    }
}
