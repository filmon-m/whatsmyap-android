package io.github.filmonm.whatsmyap.wlc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the SSH session does with the text a WLC sends back. */
class CliTest {

    @Suppress("UNCHECKED_CAST")
    private val commands = Json.parse(javaClass.classLoader!!.getResource("wlc/commands.json")!!.readText()) as Map<String, Map<String, String>>

    @Test fun prompts() {
        assertTrue(Cli.looksLikePrompt("banner\nes-sample-w1#"))
        assertTrue(Cli.looksLikePrompt("(of-samplelab-w1) *[mynode] #"))
        assertTrue(Cli.looksLikePrompt("(of-samplelab-w1) [mynode] #\n"))
        assertTrue(Cli.looksLikePrompt("(aruba-md) #"))
        assertTrue(!Cli.looksLikePrompt("##########################"))
        assertTrue(!Cli.looksLikePrompt("UNAUTHORIZED ACCESS TO THIS NETWORK DEVICE IS PROHIBITED."))
        assertTrue(!Cli.looksLikePrompt("es-sample-w1#show ap summary"))
        val aruba = Cli.promptFor("aruba8", "of-samplelab-w1")
        assertTrue(aruba.matches("(of-samplelab-w1) *[mynode] #"))
        assertTrue(aruba.matches("(of-samplelab-w1) [mynode] #"))
        assertTrue(!aruba.matches("(other-w1) [mynode] #"))
        val cisco = Cli.promptFor("cisco9800", "es-sample-w1")
        assertTrue(cisco.matches("es-sample-w1#"))
        assertTrue(!cisco.matches("es-sample-w1#show ap summary"))
        assertEquals("of-samplelab-w1", Cli.ARUBA_PROMPT.find("(of-samplelab-w1) *[mynode] #")!!.groupValues[1])
    }

    @Test fun clean() {
        assertEquals("AP Name  : AP14\nnext\n", Cli.clean("\u001B[0mAP Name  : AP14\r\nnext\r\n"))
        assertEquals("show ap", Cli.clean("show apx\b"))
    }

    /** Every captured command, sent back the way the WLC prints it (echo, output, prompt), comes out unchanged. */
    @Test fun bodyRoundTrip() {
        for ((vendor, prompt) in listOf("cisco9800" to "es-sample-w1#", "aruba8" to "(of-samplelab-w1) *[mynode] #")) {
            val name = if (vendor == "aruba8") "of-samplelab-w1" else "es-sample-w1"
            val p = Cli.promptFor(vendor, name)
            val table = commands.getValue(if (vendor == "aruba8") "aruba" else "cisco")
            for ((cmd, out) in table) {
                val wire = Cli.clean("$prompt$cmd\r\n" + out.replace("\n", "\r\n") + prompt)
                assertEquals("$vendor: $cmd", out, Cli.body(wire, cmd, p))
            }
        }
    }
}
