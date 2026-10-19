package io.github.filmonm.whatsmyap.wlc

import java.util.TreeMap

/** Which AP a BSSID belongs to, and how sure we are. */
data class BssHit(
    val ap: String,
    /** "exact" (the WLC lists this BSSID) or "radio" (inside that AP's radio MAC block). */
    val how: String,
    val ssid: String = "",
    val band: String = "",
    val location: String = "",
)

/**
 * The controller's BSSID -> AP map, for checking a survey after the fact (the tablet that took it may be long gone).
 *
 * Aruba  show ap bss-table   lists every BSSID with its AP name: exact matches.
 * Cisco  show ap summary     lists each AP's base radio MAC; the AP's BSSIDs sit just above it
 *                            (radio bcab.f59a.fe60 -> BSSIDs ...fe60 to ...fe7f), and APs are at least 0x20 apart.
 */
class BssTable(
    private val exact: Map<Long, BssHit>,
    private val radios: TreeMap<Long, BssHit>,
) {
    val aps: Int get() = (exact.values.map { it.ap } + radios.values.map { it.ap }).toSet().size
    val isEmpty get() = exact.isEmpty() && radios.isEmpty()

    fun find(bssid: String?): BssHit? {
        val b = mac(bssid) ?: return null
        exact[b]?.let { return it }
        radios.floorEntry(b)?.let { (base, hit) -> if (b - base < RADIO_BLOCK) return hit.copy(how = "radio") }
        // Aruba fallback: another BSSID of the same radio (same first 5 bytes) is listed
        if (exact.isNotEmpty()) {
            val prefix = b ushr 8
            exact.entries.firstOrNull { (k, _) -> k ushr 8 == prefix }?.let { return it.value.copy(how = "radio", ssid = "", band = "") }
        }
        return null
    }

    /**
     * As a site AP list for testers without a WLC login:
     *   # What's My AP site list / # name: ... / # vendor: ... / # created: ...
     *   kind,mac,ap,ssid,band,location      (kind: exact = a BSSID, radio = a Cisco base radio MAC)
     */
    fun toCsv(name: String, vendor: String, created: String): String = buildString {
        appendLine("# What's My AP site list"); appendLine("# name: $name"); appendLine("# vendor: $vendor"); appendLine("# created: $created")
        appendLine("kind,mac,ap,ssid,band,location")
        fun q(v: String) = if (v.any { it == ',' || it == '"' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
        fun hex(m: Long) = "%012x".format(m).chunked(2).joinToString(":")
        exact.forEach { (m, h) -> appendLine(listOf("exact", hex(m), h.ap, h.ssid, h.band, h.location).joinToString(",") { q(it) }) }
        radios.forEach { (m, h) -> appendLine(listOf("radio", hex(m), h.ap, h.ssid, h.band, h.location).joinToString(",") { q(it) }) }
    }

    companion object {
        const val RADIO_BLOCK = 0x20L

        /** A saved site list -> (its # name/vendor/created lines, the table). */
        fun fromCsv(text: String): Pair<Map<String, String>, BssTable> {
            val meta = LinkedHashMap<String, String>()
            val exact = HashMap<Long, BssHit>()
            val radios = TreeMap<Long, BssHit>()
            for (line in text.lines()) {
                if (line.startsWith("#")) {
                    line.removePrefix("#").split(":", limit = 2).takeIf { it.size == 2 }?.let { (k, v) -> meta[k.trim()] = v.trim() }
                    continue
                }
                val f = splitCsv(line)
                if (f.size < 3 || f[0] !in setOf("exact", "radio")) continue
                val m = mac(f[1]) ?: continue
                val hit = BssHit(f[2], f[0], ssid = f.getOrElse(3) { "" }, band = f.getOrElse(4) { "" }, location = f.getOrElse(5) { "" })
                if (f[0] == "exact") exact[m] = hit else radios[m] = hit
            }
            return meta to BssTable(exact, radios)
        }

        private fun splitCsv(line: String): List<String> {
            val out = mutableListOf<String>()
            val f = StringBuilder()
            var q = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    q && c == '"' && line.getOrNull(i + 1) == '"' -> { f.append('"'); i++ }
                    c == '"' -> q = !q
                    c == ',' && !q -> { out += f.toString(); f.clear() }
                    else -> f.append(c)
                }
                i++
            }
            out += f.toString()
            return out
        }
        private val MAC_DOT = Regex("""^[0-9a-fA-F]{4}\.[0-9a-fA-F]{4}\.[0-9a-fA-F]{4}$""")

        fun mac(s: String?): Long? = Parse.normMac(s).takeIf { it.length == 12 }?.toLong(16)

        /** Aruba 'show ap bss-table'. */
        fun aruba(text: String): BssTable {
            val exact = HashMap<Long, BssHit>()
            for (rows in Parse.dashTable(text)) for (r in rows) {
                val low = r.mapKeys { it.key.lowercase() }
                val bss = mac(low["bss"]) ?: continue
                val ap = low["ap name"]?.ifEmpty { null } ?: continue
                val band = Parse.bandFromText((low["band/ht-mode/bandwidth"] ?: "").substringBefore('/').replace("GHz", " GHz"))
                exact[bss] = BssHit(ap, "exact", ssid = low["ess"] ?: "", band = band)
            }
            return BssTable(exact, TreeMap())
        }

        /** Cisco 'show ap summary': AP Name, Slots, AP Model, Ethernet MAC, Radio MAC, CC, RD, IP Address, State, Location. */
        fun cisco(text: String): BssTable {
            val radios = TreeMap<Long, BssHit>()
            for (line in text.lines()) {
                val t = line.trim().split(Regex("\\s+"), limit = 10)
                if (t.size < 5 || !MAC_DOT.matches(t[3]) || !MAC_DOT.matches(t[4])) continue
                val location = t.getOrNull(9)?.trim() ?: ""
                radios[mac(t[4])!!] = BssHit(t[0], "radio", location = location)
            }
            return BssTable(emptyMap(), radios)
        }
    }
}
