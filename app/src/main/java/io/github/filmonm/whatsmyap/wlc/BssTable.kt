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

    companion object {
        const val RADIO_BLOCK = 0x20L
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
