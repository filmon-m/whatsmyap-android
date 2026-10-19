package io.github.filmonm.whatsmyap.wlc

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Holds the Kotlin WLC code to the Termux prototype's answers.
 *
 * resources/wlc/commands.json  real captures (usernames, hostnames, org name and IPs replaced), split per command
 * resources/wlc/expected.json  what the Python parsers, health checks and log rows made of the same text
 *                              (written by gen_fixtures.py from the prototype)
 */
class WlcParityTest {

    private fun resource(name: String) = javaClass.classLoader!!.getResource("wlc/$name")!!.readText()

    @Suppress("UNCHECKED_CAST")
    private val commands = Json.parse(resource("commands.json")) as Map<String, Map<String, String>>

    @Suppress("UNCHECKED_CAST")
    private val expected = Json.parse(resource("expected.json")) as Map<String, Any?>

    private fun case(name: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return expected[name] as Map<String, Any?>
    }

    /** A session that answers from the captures; unknown commands come back rejected, like on a WLC. */
    inner class Fake(vendor: String, override val name: String) : Runner {
        private val table = commands.getValue(vendor)
        val asked = mutableListOf<String>()
        override fun run(cmd: String): String {
            asked += cmd
            return table[cmd.words().joinToString(" ")] ?: "% Invalid input detected at '^' marker.\n"
        }
    }

    // ------------------------------------------------------------------ comparison
    /** Numbers as doubles, private "_" keys and null values dropped, tuples/lists alike. */
    private fun canon(x: Any?): Any? = when (x) {
        null -> null
        is Map<*, *> -> x.entries.filter { it.value != null && !it.key.toString().startsWith("_") }
            .associate { it.key.toString() to canon(it.value) }.toSortedMap()
        is Collection<*> -> x.map { canon(it) }
        is Set<*> -> x.map { canon(it) }
        is Number -> x.toDouble()
        is Client -> canon(clientMap(x))
        is Link -> canon(x.toFacts())
        is Row -> listOf(x.label, x.text, x.status)
        else -> x
    }

    private fun diff(path: String, a: Any?, b: Any?, out: MutableList<String>) {
        when {
            a is Map<*, *> && b is Map<*, *> -> (a.keys + b.keys).forEach { diff("$path.$it", a[it], b[it], out) }
            a is List<*> && b is List<*> && a.size == b.size -> a.indices.forEach { diff("$path[$it]", a[it], b[it], out) }
            a != b -> out += "$path: python=$a kotlin=$b"
        }
    }

    private fun same(what: String, python: Any?, kotlin: Any?) {
        val out = mutableListOf<String>()
        diff(what, canon(python), canon(kotlin), out)
        if (out.isNotEmpty()) fail(out.joinToString("\n"))
    }

    private fun clientMap(c: Client) = mapOf(
        "ap" to c.ap, "bssid" to c.bssid, "band" to c.band, "channel" to c.channel, "rssi_wlc" to c.rssiWlc,
        "snr_wlc" to c.snrWlc, "ssid" to c.ssid, "ap_ip" to c.apIp, "state" to c.state, "raw" to c.raw,
    )

    // ------------------------------------------------------------------ tests
    @Test fun ciscoFindClient() {
        val c = case("cisco_find_client")
        val fake = Fake("cisco", "ms-sample2-w1")
        same("result", c["result"], Cisco9800(fake).findClient(c["ip"] as String))
        same("asked", c["asked"], fake.asked)
    }

    @Test fun arubaFindClient() {
        for (name in listOf("aruba_find_client", "aruba_find_client_missing")) {
            val c = case(name)
            val fake = Fake("aruba", "of-samplelab-w1")
            same("$name.result", c["result"], Aruba8(fake).findClient(c["ip"] as String))
            same("$name.asked", c["asked"], fake.asked)
        }
    }

    @Test fun arubaApLink() {
        val c = case("aruba_ap_link")
        val fake = Fake("aruba", "of-samplelab-w1")
        same("result", c["result"], Aruba8(fake).apLink("AP14"))
        same("asked", c["asked"], fake.asked)
    }

    @Test fun apHealth() {
        for (name in listOf("cisco_ap_health_1001_AP49", "aruba_ap_health_AP14", "cisco_ap_health_1001_AP99")) {
            val c = case(name)
            val vendor = c["vendor"] as String
            val fake = Fake(vendor, c["wlc"] as String)
            val drv = if (vendor == "cisco") Cisco9800(fake) else Aruba8(fake)
            val ap = c["ap"] as String
            val f = drv.apHealth(ap)
            same("$name.facts", c["facts"], f)
            if (f["not_found"] == true) { same("$name.asked", c["asked"], fake.asked); continue }
            val link = (if (vendor == "aruba") drv.apLink(ap) else null) ?: Link.of(f["eth"])
            same("$name.asked", c["asked"], fake.asked)
            same("$name.link", c["link"], link)
            val (rows, overall) = Health.evaluateAp(f, link, "5")
            same("$name.rows", c["rows"], rows)
            assertEquals("$name.overall", c["overall"], overall)
            same("$name.log", c["log"], Health.apLogRows(fake.name, drv.vendor, f, rows, overall, link))
        }
    }

    @Test fun ciscoDot11() = same("dot11", case("cisco_dot11_24")["result"],
        CiscoAp.dot11(commands.getValue("cisco").getValue("show ap name 1001_AP85 config dot11 24ghz")))

    @Test fun wlcHealth() {
        for (vendor in listOf("cisco", "aruba")) {
            val c = case("${vendor}_wlc_health")
            val fake = Fake(vendor, c["wlc"] as String)
            val drv = if (vendor == "cisco") Cisco9800(fake) else Aruba8(fake)
            val f = drv.wlcSummary()
            same("$vendor.asked", c["asked"], fake.asked)
            val seen = HashMap<String, Int>()
            val (rows, overall) = Health.evaluateWlc(f, fake.name, { wlc, joined, reset ->
                val best = seen[wlc] ?: 0
                if (reset || joined > best) { seen[wlc] = joined; joined } else best
            })
            same("$vendor.facts", c["facts"], f)
            same("$vendor.rows", c["rows"], rows)
            assertEquals("$vendor.overall", c["overall"], overall)
            same("$vendor.log", c["log"], Health.wlcLogRows(fake.name, drv.vendor, f, rows, overall))
        }
    }

    @Test fun names() {
        @Suppress("UNCHECKED_CAST")
        (expected["ap_number"] as Map<String, Any?>).forEach { (name, n) -> same("ap_number($name)", n, Parse.apNumber(name)) }
        @Suppress("UNCHECKED_CAST")
        (expected["other_name"] as List<List<String?>>).forEach { (cur, typed, want) ->
            assertEquals("other_name($cur, $typed)", want, Health.otherName(cur, typed!!))
        }
    }
}

/** Just enough JSON to read the fixtures (org.json is a stub in local unit tests). */
object Json {
    fun parse(s: String): Any? = P(s).run { value().also { ws(); require(i == s.length) { "trailing data at $i" } } }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> if (c == '-' || c.isDigit()) num() else error("bad JSON at $i: $c")
            }
        }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++; ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws(); require(s[i] == ':'); i++
                m[k] = value(); ws()
                if (s[i] == ',') { i++; continue }
                require(s[i] == '}'); i++; return m
            }
        }
        fun arr(): List<Any?> {
            val l = mutableListOf<Any?>()
            i++; ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l += value(); ws()
                if (s[i] == ',') { i++; continue }
                require(s[i] == ']'); i++; return l
            }
        }
        fun str(): String {
            val b = StringBuilder()
            i++
            while (s[i] != '"') {
                if (s[i] == '\\') {
                    i++
                    when (val e = s[i]) {
                        'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                        'u' -> { b.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                        else -> b.append(e)
                    }
                } else b.append(s[i])
                i++
            }
            i++
            return b.toString()
        }
        fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble()
        }
    }
}
