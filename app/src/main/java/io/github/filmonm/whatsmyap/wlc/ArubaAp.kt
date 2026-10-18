package io.github.filmonm.whatsmyap.wlc

/**
 * Aruba 8.x - health of ONE AP (port of aruba8_ap.py, confirmed on 8.10 / AP-215).
 *
 * show ap details ap-name <ap>              status, uptime, model, serial, IP, reboots, location
 * show ap active ap-name <ap>               per radio Ch/EIRP/MaxEIRP/Clients, flags
 * show ap radio-summary ap-name <ap>        per radio noise floor / utilization / interference
 * show user-table ap-name <ap>              clients -> per SSID and band
 * show ap debug system-status ap-name <ap>  AP CPU/memory, crashes, rebootstraps, PoE, Ethernet
 */
object ArubaAp {

    private const val MAC = """[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5}"""

    fun commands(ap: String) = linkedMapOf(
        "details" to "show ap details ap-name $ap",
        "active" to "show ap active ap-name $ap",
        "radios" to "show ap radio-summary ap-name $ap",
        "users" to "show user-table ap-name $ap",
        "system" to "show ap debug system-status ap-name $ap",
    )

    private fun band(s: String?) = Parse.bandFromText((s ?: "").replace("GHz", " GHz"))

    /** 161d:20h:9m:38s -> seconds (null when nothing parses). */
    fun uptime(s: String?): Int? {
        val units = mapOf("d" to 86400, "h" to 3600, "m" to 60, "s" to 1)
        val total = rx("""(\d+)([dhms])""").findAll(s ?: "").sumOf { it.groupValues[1].toInt() * units.getValue(it.groupValues[2]) }
        return if (total == 0) null else total
    }

    /** Aruba channel suffix: + or - = 40 MHz, E = 80, S = 160, none = 20. */
    fun widthOf(ch: String?): Int {
        val c = ch ?: ""
        return when {
            "S" in c -> 160
            "E" in c -> 80
            "+" in c || "-" in c -> 40
            else -> 20
        }
    }

    /** 'Item   Value' rows (2+ spaces apart) -> map, first occurrence wins, keys lower-cased. */
    private fun kv2(text: String): Map<String, String> {
        val kv = LinkedHashMap<String, String>()
        for (line in text.lines()) {
            val m = match("""(\S.*?)\s{2,}(\S.*?)\s*$""", line) ?: continue
            kv.putIfAbsent(m.groupValues[1].trim().trimEnd(':').lowercase(), m.groupValues[2])
        }
        return kv
    }

    fun details(text: String): Facts {
        if ("Basic Information" !in text) return facts()
        val kv = kv2(text)
        val res = facts("state" to kv["status"], "uptime_s" to uptime(kv["up time"]),
            "ip" to kv["ap ip address"], "group" to kv["group"], "serial" to kv["serial #"])
        kv["ap type"]?.takeIf { it.isNotEmpty() }?.let { res["model"] = "AP-$it" }
        val loc = kv["snmp syslocation"]
        if (!loc.isNullOrEmpty() && loc != "N/A") res["location"] = loc.replace(Regex("""\s+,"""), ",")
        for (k in listOf("reboots", "bootstraps")) if (kv[k].isDigits()) res[k] = kv.getValue(k).toInt()
        return res.compact()
    }

    private val RADIO = rx("""\b(\w+):(\d(?:\.\d)?GHz)[\w-]*:(\S+?)/([\d.]+)/([\d.]+)/(\d+)""")

    /** Active AP table -> radios with channel, EIRP and clients, plus the AP flags. */
    fun active(text: String): Facts {
        val radios = RADIO.findAll(text).map { m ->
            val g = m.groupValues
            facts("band" to band(g[2]), "mode" to g[1], "channel" to g[3].replace(Regex("[^\\d]"), ""),
                "width" to widthOf(g[3]), "eirp_dbm" to g[4].toDouble(), "max_eirp_dbm" to g[5].toDouble(), "clients" to g[6].toInt())
        }.toList()
        if (radios.isEmpty()) return facts()
        val res = facts("radios_active" to radios)
        loop@ for (rows in Parse.dashTable(text)) {
            for (r in rows) {
                if ("Flags" in r) {
                    res["flags"] = r["Flags"]
                    if (!truthy(res["uptime_s"])) res["uptime_s"] = uptime(r["Uptime"])
                    break
                }
            }
            if ("flags" in res) break@loop
        }
        return res
    }

    fun radioSummary(text: String): Facts {
        val rows = mutableListOf<Facts>()
        for (line in text.lines()) {
            val m = search("""\s(2\.4GHz|5GHz|6GHz)\s+(\S+)\s+([\d.]+)/([\d.]+)\s+(-?\d+)/(\d+)/(\d+)""", line) ?: continue
            val g = m.groupValues
            val ch = g[2].substringAfterLast(":")
            rows += facts("band" to band(g[1]), "mode" to g[2].split(":")[0].trimEnd('^', '?'),
                "channel" to ch.replace(Regex("[^\\d]"), ""), "width" to widthOf(ch),
                "eirp_dbm" to g[3].toDouble(), "max_eirp_dbm" to g[4].toDouble(),
                "noise_dbm" to g[5].toInt(), "util_pct" to g[6].toInt(), "interf_pct" to g[7].toInt())
        }
        return if (rows.isNotEmpty()) facts("radios_summary" to rows) else facts()
    }

    fun users(text: String): Facts {
        if ("User Entries" !in text && "Users" !in text) return facts()
        val rows = mutableListOf<Facts>()
        val seen = HashSet<String>()
        for (line in text.lines()) {
            val m = search("""^\s*\S+\s+($MAC)\s.*?\s(\S+)/($MAC)/(\S+)""", line) ?: continue
            if (seen.add(m.groupValues[1])) rows += facts("ssid" to m.groupValues[2], "band" to band(m.groupValues[4]))
        }
        return facts("client_rows" to rows)
    }

    fun section(text: String, start: String, end: String? = null): String {
        val i = text.indexOf(start)
        if (i < 0) return ""
        val j = if (end != null) text.indexOf(end, i + start.length) else -1
        return text.substring(i, if (j > 0) j else text.length)
    }

    fun system(text: String): Facts {
        val res = facts()
        val cm = section(text, "CPU and Memory Usage", "Peak CPU Util")
        search("""^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\s+(\d+)\s+(\d+)\s*$""", cm, "m")?.let {
            res["cpu_pct"] = it.groupValues[1].toInt(); res["mem_pct"] = it.groupValues[2].toInt()
        }
        val pk = section(text, "Peak CPU Util", "Output of top")
        search("""^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\s+(\d+)\s+\d+\s*$""", pk, "m")?.let { res["cpu_peak_pct"] = it.groupValues[1].toInt() }
        val crash = section(text, "Crash Information", "Channel Power Request")
        if (crash.isNotEmpty()) res["crash"] = "(none found)" !in crash
        val reboot = section(text, "Reboot Information", "Rebootstrap Information")
        if (reboot.isNotEmpty()) {
            val lines = pyLines(reboot).drop(2).filter { it.isNotBlank() && !it.trim().all { c -> c == '-' } }
            res["reboot_info"] = if (lines.any { "(none found)" in it }) null else lines.lastOrNull()?.trim()?.take(120)
        }
        val rb = section(text, "Rebootstrap Information", "HA Failover Information")
        val events = mutableListOf<List<String>>()
        for (line in pyLines(rb)) {
            val m = match("""(\d{4}-\d\d-\d\d) (\d\d:\d\d:\d\d)\s+(.*)$""", line) ?: continue
            val toks = m.groupValues[3].words()
            events += listOf("${m.groupValues[1]} ${m.groupValues[2]}", toks.drop(6).joinToString(" ").ifEmpty { "?" })
        }
        if (rb.isNotEmpty()) res["rebootstraps"] = events
        val pw = section(text, "Power Status", "Bandwidth Contracts")
        if (pw.isNotEmpty()) {
            fun g(k: String) = group("""^$k\s*:\s*(.+?)\s*$""", pw, flags = "m")
            res["poe_type"] = g("Power Supply")
            val lldp = g("LLDP Power") ?: ""
            res["poe_watts"] = group("""([\d.]+)\s*W""", lldp)?.toDouble()
            res["poe_state"] = g("Current Operational State")
            res["poe_hw"] = g("HW POE status")
        }
        search("""^(?:on|off)\s+(\d+)\s+(Full|Half)\s+(\S+)""", section(text, "Ethernet Duplex/Speed", "Controller Information"), "m")?.let {
            res["eth"] = facts("iface" to it.groupValues[3], "status" to "UP", "mbps" to it.groupValues[1].toInt(), "duplex" to it.groupValues[2])
        }
        return res
    }

    /** Python str.splitlines(): no trailing empty piece. */
    private fun pyLines(s: String): List<String> = s.lines().let { if (s.endsWith("\n")) it.dropLast(1) else it }

    /** active (clients, EIRP) + radio-summary (noise/util/interference) -> one list by band. */
    fun mergeRadios(f: Facts) {
        val act = (f.remove("radios_active") as List<*>?).orEmpty().map { @Suppress("UNCHECKED_CAST") (it as Facts) }
        val summ = LinkedHashMap<Any?, Facts>()
        (f.remove("radios_summary") as List<*>?).orEmpty().forEach { @Suppress("UNCHECKED_CAST") val r = it as Facts; summ[r["band"]] = r }
        val radios = mutableListOf<Facts>()
        for (a in act) {
            val r: Facts = LinkedHashMap(summ.remove(a["band"]) ?: facts())
            a.forEach { (k, v) -> if (v != null) r[k] = v }
            radios += r
        }
        radios += summ.values
        for (r in radios) {
            val mode = r.str("mode") ?: "AP"
            r["enabled"] = true
            r["up"] = mode == "AP"                       // AM = air monitor, SM = spectrum monitor
            r["role"] = mapOf("AP" to "", "AM" to "air monitor", "SM" to "spectrum monitor")[mode] ?: r["mode"]
        }
        val order = mapOf("5" to 0, "6" to 1, "2.4" to 2)
        f["radios"] = radios.sortedBy { order[it["band"]] ?: 9 }
    }

    private val PARSERS: Map<String, (String) -> Facts> = mapOf(
        "details" to ::details, "active" to ::active, "radios" to ::radioSummary, "users" to ::users, "system" to ::system,
    )

    fun collect(run: (String) -> String?, ap: String): Facts {
        val f = facts("ap" to ap)
        val missing = mutableListOf<String>()
        for ((key, cmd) in commands(ap)) {
            val got = runCatching { run(cmd)?.takeIf { it.isNotEmpty() }?.let { PARSERS.getValue(key)(it) } ?: facts() }.getOrElse { facts() }
            if (got.isEmpty()) missing += key
            for ((k, v) in got) if (k !in f || f[k] == null || f[k] == "") f[k] = v
        }
        if ("details" in missing && "active" in missing) f["not_found"] = true
        mergeRadios(f)
        if ((f.str("flags") ?: "").let { it.isNotEmpty() && "p" in it }) f["poe_restricted"] = true   // 'p' = PoE restriction mode
        f.str("poe_state")?.takeIf { it.isNotEmpty() }?.let {
            val st = it.lowercase()
            if ("restrict" in st && "no restriction" !in st) f["poe_restricted"] = true
        }
        if ("poe_restricted" !in f) f["poe_restricted"] = if (truthy(f["poe_state"])) false else null
        f["missing"] = missing
        return f
    }
}
