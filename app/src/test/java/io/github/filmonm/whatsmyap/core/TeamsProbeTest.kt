package io.github.filmonm.whatsmyap.core

import io.github.filmonm.whatsmyap.data.TeamsProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TeamsProbeTest {
    private val tx = ByteArray(12) { (it + 1).toByte() }

    @Test fun bindingRequestIsAValidStunHeader() {
        val b = TeamsProbe.bindingRequest(tx)
        assertEquals(20, b.size)
        assertEquals(0x0001, ((b[0].toInt() and 0xff) shl 8) or (b[1].toInt() and 0xff))
        assertEquals("2112a442", b.copyOfRange(4, 8).joinToString("") { "%02x".format(it) })
    }

    @Test fun matchesSuccessAndErrorResponsesOnly() {
        val resp = TeamsProbe.bindingRequest(tx).also { it[0] = 0x01; it[1] = 0x01 }        // Binding Success
        assertEquals(tx.joinToString("") { "%02x".format(it) }, TeamsProbe.responseTxId(resp, resp.size))
        val err = TeamsProbe.bindingRequest(tx).also { it[0] = 0x01; it[1] = 0x11 }         // Binding Error
        assertEquals(tx.joinToString("") { "%02x".format(it) }, TeamsProbe.responseTxId(err, err.size))
        val req = TeamsProbe.bindingRequest(tx)                                              // our own request echoed
        assertNull(TeamsProbe.responseTxId(req, req.size))
        assertNull(TeamsProbe.responseTxId(resp, 12))
    }

    @Test fun medianAndJitterIgnoreLostPackets() {
        val (median, jitter) = TeamsProbe.stats(listOf(20.0, null, 24.0, 22.0, null))
        assertEquals(22.0, median!!, 0.001)
        assertEquals(3.0, jitter!!, 0.001)                     // |24-20| and |22-24| -> 3
    }
}
