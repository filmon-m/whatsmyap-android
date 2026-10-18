package io.github.filmonm.whatsmyap.wlc

/** What a driver needs from the SSH session (a fake one answers from captures in the tests). */
interface Runner {
    /** Controller name from its prompt, e.g. es-braddockdrive-w1. */
    val name: String
    /** Run a show command; throws if the session is gone. */
    fun run(cmd: String): String
    /** A command that may not exist on this platform/version: null if rejected or failed. */
    fun tryRun(cmd: String): String? = runCatching { run(cmd) }.getOrNull()?.takeUnless { ERROR_OUTPUT.containsMatchIn(it) }

    companion object {
        val ERROR_OUTPUT = rx("""Invalid input|Parse error|not supported|Incomplete command""", "i")
    }
}

/** Where the WLC says a client is. */
data class Client(
    val ap: String?, val bssid: String, val band: String, val channel: String,
    val rssiWlc: Int?, val snrWlc: Int?, val ssid: String, val apIp: String, val state: String,
    val raw: List<String>?,
)

/** An AP's Ethernet link. */
data class Link(val iface: String, val status: String, val mbps: Int, val duplex: String) {
    fun toFacts(): Facts = facts("iface" to iface, "status" to status, "mbps" to mbps, "duplex" to duplex)

    companion object {
        fun of(f: Any?): Link? = (f as? Map<*, *>)?.let {
            Link(it["iface"]?.toString() ?: "", it["status"]?.toString() ?: "", (it["mbps"] as Number?)?.toInt() ?: 0, it["duplex"]?.toString() ?: "")
        }
    }
}

/** What every vendor driver answers. Screens only talk to these. */
abstract class Driver(val s: Runner) {
    abstract val vendor: String          // "cisco9800" | "aruba8" - same values the logs and dashboard use
    abstract val label: String
    val name get() = s.name

    abstract fun findClient(ip: String): Client?
    abstract fun apLink(ap: String): Link?
    abstract fun wlcSummary(): Facts
    abstract fun apHealth(ap: String): Facts
    /** How many commands apHealth + apLink send (for the progress bar). */
    abstract val apHealthCommands: Int

    companion object {
        fun forVendor(vendor: String, s: Runner): Driver = when (vendor) {
            "cisco9800" -> Cisco9800(s)
            "aruba8" -> Aruba8(s)
            else -> error("Unknown WLC type $vendor")
        }
    }
}

// ------------------------------------------------------------------------------------------ Cisco
class Cisco9800(s: Runner) : Driver(s) {
    override val vendor = "cisco9800"
    override val label = "Cisco 9800"
    override val apHealthCommands = 8

    override fun findClient(ip: String): Client? {
        val out = s.run("show wireless client summary detail ipv4 | include $ip")
        var mac: String? = null
        for (line in out.lines()) {
            val toks = line.words()
            if (toks.isNotEmpty() && ip in toks && MAC_DOT.matches(toks[0])) { mac = toks[0]; break }   // exact IP, not .13 vs .135
        }
        if (mac == null) return null
        val kv = Parse.parseKv(s.run("show wireless client mac-address $mac detail"))
        val rssi = group("""-?\d+""", kv["radio signal strength indicator"] ?: "", 0)
        val snr = group("""-?\d+""", kv["signal to noise ratio"] ?: "", 0)
        return Client(
            ap = kv["ap name"], bssid = Parse.colonMac(kv["bssid"] ?: ""), band = Parse.bandFromText(kv["protocol"] ?: ""),
            channel = kv["channel"] ?: "", rssiWlc = rssi?.toInt(), snrWlc = snr?.toInt(), apIp = "",
            ssid = kv["wireless lan network name (ssid)"] ?: "", state = kv["client state"] ?: "", raw = null,
        )
    }

    override fun wlcSummary(): Facts = CiscoHealth.collect(s::tryRun)
    override fun apHealth(ap: String): Facts = CiscoAp.collect(s::tryRun, ap)

    // keep ALL "AP Name" lines - they mark where each AP's block ends
    override fun apLink(ap: String): Link? = parseApEthernet(s.run("show ap ethernet statistics | include AP Name|Mbps"), ap)

    companion object {
        val MAC_DOT = rx("""[0-9a-f]{4}\.[0-9a-f]{4}\.[0-9a-f]{4}""", "i")

        /** 'show ap ethernet statistics | include AP Name|Mbps' -> uplink of the exact AP. */
        fun parseApEthernet(text: String, ap: String): Link? {
            val ifaces = mutableListOf<Link>()
            var inBlock = false
            for (line in text.lines()) {
                val m = match("""\s*AP Name\s*:\s*(\S+)\s*$""", line)
                if (m != null) {
                    if (inBlock) break                                  // finished our AP's block
                    inBlock = m.groupValues[1] == ap
                    continue
                }
                if (inBlock) {
                    match("""\s*(\S+)\s+(UP|DOWN)\s+(\d+)\s*Mbps\s+(\S+)""", line, "i")?.let {
                        ifaces += Link(it.groupValues[1], it.groupValues[2].uppercase(), it.groupValues[3].toInt(), it.groupValues[4])
                    }
                }
            }
            return ifaces.firstOrNull { it.status == "UP" } ?: ifaces.firstOrNull()
        }
    }
}

// ------------------------------------------------------------------------------------------ Aruba
/**
 * Aruba ArubaOS 8.x (confirmed against 8.10.0.15, Aruba9240 standalone).
 * findClient: show user ip <ip> - the user detail has "Essid: ..., Bssid: ... AP name/group: AP14/... Phy-type: 5GHz-VHT-40"
 *             plus "Channel 108" and "Client SNR 54". No RSSI is reported.
 * apLink:     show ap port status ap-name <ap>   ("1 Gb/s  full")
 */
class Aruba8(s: Runner) : Driver(s) {
    override val vendor = "aruba8"
    override val label = "Aruba 8.x"
    override val apHealthCommands = 6

    override fun findClient(ip: String): Client? {
        parseUser(s.tryRun("show user ip $ip") ?: "", ip)?.let { return it }
        // Mobility Conductor keeps users in a global table instead (unconfirmed format)
        val out = s.tryRun("show global-user-table list ip-addr $ip")
        if (out != null && ip in out) {
            val kv = Parse.parseKv(out)
            val ap = kv["ap name"] ?: kv["ap-name"]
            return Client(ap = ap, bssid = Parse.colonMac(kv["bssid"] ?: ""), band = "", channel = "", rssiWlc = null, snrWlc = null,
                ssid = kv["essid"] ?: "", apIp = "", state = "",
                raw = if (ap.isNullOrEmpty()) out.lines().filter { it.isNotBlank() }.take(8) else null)
        }
        return null
    }

    override fun wlcSummary(): Facts = ArubaHealth.collect(s::tryRun)
    override fun apHealth(ap: String): Facts = ArubaAp.collect(s::tryRun, ap)

    override fun apLink(ap: String): Link? {
        for (cmd in listOf("show ap port status ap-name $ap", "show ap debug port status ap-name $ap")) {
            val out = s.tryRun(cmd)
            if (!out.isNullOrEmpty()) parsePort(out)?.let { return it }
        }
        return null
    }

    companion object {
        private const val MAC = """[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5}"""

        /** Port status table -> first port that is up. */
        fun parsePort(text: String): Link? {
            for (rows in Parse.dashTable(text)) {
                if (rows.isEmpty()) continue
                val orig = rows[0].keys.toList()
                val cols = orig.map { it.lowercase() }
                fun col(pred: (String) -> Boolean) = cols.indexOfFirst(pred).takeIf { it >= 0 }?.let { orig[it] }
                val scol = col { "speed" in it } ?: continue
                val dcol = col { "duplex" in it }
                val ocol = col { it == "oper" || "status" in it || "link" in it }
                val icol = col { it == "port" || "interface" in it } ?: orig[0]
                val tcol = col { it == "type" }
                for (r in rows) {
                    if (tcol != null && (r[tcol] ?: "").uppercase() == "USB") continue
                    val up = if (ocol != null) (r[ocol] ?: "").lowercase().startsWith("up") else true
                    val mbps = Parse.speedToMbps(r[scol] ?: "")
                    if (up && mbps != null && mbps != 0) {
                        return Link(r[icol] ?: "", "UP", mbps, if (dcol != null) (r[dcol] ?: "").pyCapitalize() else "")
                    }
                }
                val down = rows.firstOrNull { tcol == null || (it[tcol] ?: "").uppercase() != "USB" }
                if (down != null && ocol != null) return Link(down[icol] ?: "", "DOWN", 0, "")
            }
            return null
        }

        /** 'show user ip <ip>' -> client, or null if this IP isn't a wireless user here. */
        fun parseUser(text: String, ip: String): Client? {
            // the user-detail block starts at "Name: ..., IP: <ip>, MAC: ..."
            val m = search("""^Name:.*?\bIP:\s*${Regex.escape(ip)}\s*,.*$""", text, "m") ?: return null
            val detail = text.substring(m.range.first)
            fun g(pat: String) = group(pat, detail, flags = "m")
            var ssid = (g("""Essid:\s*([^,\n]+?)\s*,""") ?: "").trim()
            var bssid = Parse.colonMac(g("""Bssid:\s*($MAC)""") ?: "")
            var ap = g("""AP name/group:\s*([^/\s]+)""")
            val phy = g("""Phy-type:\s*(\S+)""") ?: g("""phy_type:\s*([^,\s]+)""") ?: ""
            val band = Parse.bandFromText(phy.replace("GHz", " GHz"))
            val apIp = g("""^AP IP:\s*(\S+)""") ?: ""
            val channel = g("""^Channel\s+(\d+)\s*$""") ?: ""
            val snr = g("""^Client SNR\s+(-?\d+)\s*$""")
            // Aruba shows 0 until the AP's next stats report after you associate: that's "no reading yet"
            val snrWlc = snr?.toInt()?.takeIf { it > 0 }
            val state = if (search("""Mobility state:\s*Wireless""", detail) != null) "Wireless" else ""
            if (ap.isNullOrEmpty()) {                     // fallback: the Association Table
                for (rows in Parse.dashTable(detail)) {
                    val row = rows.firstOrNull { r -> r.keys.any { it.lowercase() == "bssid" } } ?: continue
                    val low = row.mapKeys { it.key.lowercase() }
                    ap = low["name"]?.ifEmpty { null }
                    bssid = bssid.ifEmpty { Parse.colonMac(low["bssid"] ?: "") }
                    ssid = ssid.ifEmpty { low["essid"] ?: "" }
                    break
                }
            }
            val raw = if (ap.isNullOrEmpty()) detail.lines().filter { it.isNotBlank() }.map { it.trimEnd() }.take(8) else null
            return Client(ap, bssid, band, channel, null, snrWlc, ssid, apIp, state, raw)
        }
    }
}
