package io.github.filmonm.whatsmyap.wlc

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Warn / fail limits for Debug my AP and Debug my WLC (the prototype's [aphealth] and [wlchealth]). */
object HealthLimits {
    // Debug my WLC
    var ignoreAps = ""                       // comma separated, * and ? wildcards
    var apsDownWarnPct = 2.0
    var apsDownFailPct = 10.0
    var wlcCpuWarnPct = 70.0
    var wlcCpuFailPct = 90.0
    var wlcMemWarnPct = 80.0
    var wlcMemFailPct = 90.0
    var storageWarnPct = 80.0
    var storageFailPct = 90.0
    var tempWarnC = 75.0
    var wlcUptimeWarnHours = 24.0

    // Debug my AP
    var utilWarnPct = 50.0
    var utilFailPct = 80.0
    var interferenceWarnPct = 20.0
    var interferenceFailPct = 40.0
    var noiseWarnDbm = -85.0
    var noiseFailDbm = -75.0
    var clientsWarn = 40.0
    var apCpuWarnPct = 70.0
    var apCpuFailPct = 90.0
    var apMemWarnPct = 80.0
    var apMemFailPct = 90.0
    var apUptimeWarnHours = 24.0
}

/** One line on a health screen. */
data class Row(val label: String, val text: String, val status: String?, val radio: Facts? = null)

object Health {
    val RANK = mapOf(null to 0, "PASS" to 1, "WARN" to 2, "FAIL" to 3)
    val ICON = mapOf("PASS" to "✓", "WARN" to "!", "FAIL" to "✕", null to " ")
    private const val DOT = " · "

    fun level(value: Number?, warn: Double, fail: Double): String? {
        val v = value?.toDouble() ?: return null
        return if (v >= fail) "FAIL" else if (v >= warn) "WARN" else "PASS"
    }

    fun worst(vararg states: String?): String? = states.fold(null as String?) { a, b -> if (RANK.getValue(b) > RANK.getValue(a)) b else a }

    fun fmtUptime(s: Number?): String {
        if (s == null) return "?"
        val t = s.toLong()
        val d = t / 86400
        val rem = t % 86400
        val h = rem / 3600
        return if (d != 0L) "${d}d ${h}h" else "${h}h ${(rem % 3600) / 60}m"
    }

    fun fmtMbps(m: Number?): String {
        val v = m?.toInt() ?: 0
        if (v == 0) return "?"
        return if (v >= 1000 && v % 1000 == 0) "${v / 1000} Gbps" else "$v Mbps"
    }

    fun bandLabel(b: String?) = if (b.isNullOrEmpty()) "?" else "$b GHz"

    /** es-maple-w1 -> (maple, es). Unrecognized names keep the whole name as site. */
    fun parseWlcName(name: String?): Pair<String, String> {
        val n = (name ?: "").trim()
        val m = rx("""^([a-z]{2,4})-(.+?)(?:-w\d+)?$""", "i").find(n) ?: return n to ""
        return m.groupValues[2].lowercase() to m.groupValues[1].lowercase()
    }

    /** '26' while on 1001_AP49 -> 1001_AP26 (same prefix and zero padding); names pass through. */
    fun otherName(current: String?, typedIn: String): String {
        val typed = typedIn.trim()
        if (!fullmatch("""\d+""", typed) || current.isNullOrEmpty()) return typed
        val last = rx("""\d+""").findAll(current).lastOrNull() ?: return typed
        val digits = last.value
        val new = if (digits.startsWith("0")) typed.padStart(digits.length, '0') else typed.toInt().toString()
        return current.substring(0, last.range.first) + new + current.substring(last.range.last + 1)
    }

    /** fnmatch-style: * any run, ? one character. Case-sensitive like the prototype on Linux. */
    fun globMatch(name: String, pattern: String): Boolean {
        val re = buildString {
            for (c in pattern) when (c) {
                '*' -> append(".*")
                '?' -> append(".")
                else -> append(Regex.escape(c.toString()))
            }
        }
        return Regex(re).matches(name)
    }

    // =============================================================================== Debug my AP
    fun clientsBySsid(rows: List<Facts>?): List<Facts> {
        val per = LinkedHashMap<String, Facts>()
        for (r in rows ?: emptyList()) {
            val ssid = r["ssid"] as String
            val s = per.getOrPut(ssid) { facts("ssid" to ssid, "clients" to 0, "bands" to linkedMapOf<String, Int>()) }
            s["clients"] = (s["clients"] as Int) + 1
            val band = r["band"] as String?
            @Suppress("UNCHECKED_CAST") val bands = s["bands"] as MutableMap<String, Int>
            if (!band.isNullOrEmpty()) bands[band] = (bands[band] ?: 0) + 1
        }
        return per.values.sortedBy { -(it["clients"] as Int) }
    }

    private fun bandSplit(s: Facts): String {
        @Suppress("UNCHECKED_CAST") val b = s["bands"] as Map<String, Int>
        return listOf("5", "6", "2.4").filter { (b[it] ?: 0) != 0 }.joinToString(" / ") { "$it GHz ${b[it]}" }
    }

    /** -> (text, status) for one radio. */
    fun radioText(r: Facts, f: Facts, mineBand: String?): Pair<String, String?> {
        val band = r.str("band")
        val L = HealthLimits
        if (!((r["enabled"] as Boolean?) ?: true)) {
            val tag = f.str("rf_tag")
            val why = if (!tag.isNullOrEmpty() && "disable" in tag.lowercase()) "RF tag $tag" else "disabled"
            return "off ($why)" to null
        }
        val role = r.str("role")
        if (!role.isNullOrEmpty() && role.lowercase() !in setOf("client serving", "auto")) {
            return "$role mode" + (if (truthy(r["channel"])) "${DOT}ch ${r["channel"]}" else "") to null
        }
        if (!((r["up"] as Boolean?) ?: true)) {
            val oper = if (truthy(r["oper"])) ", oper ${r["oper"]}" else ""
            return "DOWN (admin enabled$oper)" to if (band == "2.4") "WARN" else "FAIL"
        }
        val items = mutableListOf<String>()
        var st: String? = "PASS"
        var ch = "ch ${r.str("channel")?.ifEmpty { null } ?: "?"}"
        if (truthy(r["width"])) ch += " (${r["width"]} MHz)"
        items += ch
        if (r["tx_dbm"] != null) {
            items += "Tx ${r["tx_dbm"]} dBm" + if (truthy(r["tx_level"])) " (level ${r["tx_level"]})" else ""
        } else if (r["eirp_dbm"] != null) {
            items += "EIRP ${fmtG(r.num("eirp_dbm"))} dBm" + if (truthy(r["max_eirp_dbm"])) " (max ${fmtG(r.num("max_eirp_dbm"))})" else ""
        }
        r.num("util_pct")?.let { items += "util ${pyStr(it)}%"; st = worst(st, level(it, L.utilWarnPct, L.utilFailPct)) }
        r.num("noise_dbm")?.let { items += "noise ${pyStr(it)} dBm"; st = worst(st, level(it, L.noiseWarnDbm, L.noiseFailDbm)) }
        r.num("interf_pct")?.let { items += "interference ${pyStr(it)}%"; st = worst(st, level(it, L.interferenceWarnPct, L.interferenceFailPct)) }
        r.int("clients")?.let { c ->
            items += "$c client${if (c != 1) "s" else ""}"
            if (c >= L.clientsWarn) st = worst(st, "WARN")
        }
        r.int("neighbors")?.let { n ->
            var txt = "$n APs heard"
            if (truthy(r["cochannel"])) txt += "$DOT${r["cochannel"]} on same channel (loudest ${r["cochannel_best"]} dBm)"
            items += txt
        }
        if (truthy(r["radar_changes"])) items += "radar moved channel ${r["radar_changes"]}x"
        val failed = r.list("profiles_failed")
        if (!failed.isNullOrEmpty()) {
            items += "RRM " + failed.joinToString("/") { it.toString().lowercase() } + " failed"
            st = worst(st, "WARN")
        }
        if (!mineBand.isNullOrEmpty() && band == mineBand) items += "you're on this radio"
        return items.joinToString(DOT) to st
    }

    /** AP facts -> rows + overall. Radio rows carry the radio. Also sets f["_ssids"] and r["_status"] for logging. */
    fun evaluateAp(f: Facts, link: Link?, mineBand: String? = null, now: LocalDateTime = LocalDateTime.now()): Pair<List<Row>, String?> {
        val L = HealthLimits
        val rows = mutableListOf<Row>()
        val state = f.str("state")
        val ok = (state ?: "").lowercase() in setOf("up", "registered")
        var txt = state ?: "?"
        val mode = f.str("mode")
        if (!mode.isNullOrEmpty() && mode.lowercase() != "local") txt += "$DOT$mode mode"
        rows += Row("State", txt, if (state == null) null else if (ok) "PASS" else "FAIL")

        val up = f.num("uptime_s")
        val parts = mutableListOf(fmtUptime(up))
        var st: String? = if (up == null) null else if (up.toDouble() < L.apUptimeWarnHours * 3600) "WARN" else "PASS"
        if (truthy(f["joined"])) parts += "joined ${f["joined"]}"
        val cw = f.num("capwap_uptime_s")
        if (cw != null && up != null && up.toDouble() != 0.0 && up.toDouble() - cw.toDouble() > 3600) {
            parts += "rejoined WLC ${fmtUptime(cw)} ago"
            if (cw.toDouble() < L.apUptimeWarnHours * 3600) st = worst(st, "WARN")
        }
        f.int("reboots")?.let { parts += "$it reboot${if (it != 1) "s" else ""} on record" }
        rows += Row("Uptime", parts.joinToString(DOT), st)

        when {
            link == null -> rows += Row("Link", "?", null)
            link.status != "UP" -> rows += Row("Link", "DOWN (${link.iface})", "FAIL")
            else -> {
                val good = link.mbps >= 1000 && link.duplex.lowercase().startsWith("full")
                rows += Row("Link", "${fmtMbps(link.mbps)} ${link.duplex}".trim() + if (good) "" else "${DOT}check cable/switch port",
                    if (good) "PASS" else "WARN")
            }
        }

        if (truthy(f["poe_type"]) || truthy(f["poe_state"])) {
            val p = mutableListOf(f.str("poe_type")?.ifEmpty { null } ?: "PoE")
            if (truthy(f["poe_watts"])) p += "${fmtG(f.num("poe_watts"))} W (LLDP)"
            f.str("poe_state")?.takeIf { it.isNotEmpty() }?.let { p += it[0].lowercase() + it.substring(1) }
            val hw = f.str("poe_hw")
            if (!hw.isNullOrEmpty() && truthy(f["poe_type"]) && hw != f["poe_type"]) p += "hardware $hw"
            if (truthy(f["poe_restricted"])) p += "radios/ports may be limited - check switch PoE"
            rows += Row("PoE", p.joinToString(DOT), if (truthy(f["poe_restricted"])) "WARN" else "PASS")
        }

        if (f["cpu_pct"] != null || f["mem_pct"] != null) {
            val p = mutableListOf<String>()
            var s: String? = null
            f.num("cpu_pct")?.let {
                p += "CPU ${pyStr(it)}%" + (f.num("cpu_peak_pct")?.let { pk -> " (peak ${pyStr(pk)}% last hour)" } ?: "")
                s = worst(s, level(it, L.apCpuWarnPct, L.apCpuFailPct))
            }
            f.num("mem_pct")?.let {
                p += "memory ${pyStr(it)}%"
                s = worst(s, level(it, L.apMemWarnPct, L.apMemFailPct))
            }
            rows += Row("AP load", p.joinToString(DOT), s)
        }

        if ("crash" in f || "rebootstraps" in f) {
            val p = mutableListOf<String>()
            var s: String? = "PASS"
            if ("crash" in f) {
                val crash = f["crash"] == true
                p += if (crash) "crash on record" else "no crashes"
                if (crash) s = "WARN"
            }
            if (truthy(f["reboot_info"])) p += "last reboot: ${f["reboot_info"]}"
            @Suppress("UNCHECKED_CAST") val ev = f["rebootstraps"] as List<List<String>>?
            if (!ev.isNullOrEmpty()) {
                val (whenAt, why) = ev.last()
                p += "${ev.size} rejoin${if (ev.size != 1) "s" else ""} (rebootstrap), last ${whenAt.take(16)} - $why"
                runCatching {
                    val t = LocalDateTime.parse(whenAt, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    if (java.time.Duration.between(t, now).seconds < L.apUptimeWarnHours * 3600) s = worst(s, "WARN")
                }
            } else if (ev != null) {
                p += "no rejoins"
            }
            rows += Row("Stability", p.joinToString(DOT), s)
        }

        for (r in f.maps("radios") ?: emptyList()) {
            val (t, s) = radioText(r, f, mineBand)
            r["_status"] = s
            rows += Row(if (truthy(r["band"])) bandLabel(r.str("band")) else "Radio", t, s, r)
        }

        if ("client_rows" in f) {
            val ssids = clientsBySsid(f.maps("client_rows"))
            f["_ssids"] = ssids
            val total = f.maps("client_rows")?.size ?: 0
            val one = ssids.map { s -> "${s["ssid"]} ${s["clients"]}" + bandSplit(s).let { if (it.isNotEmpty()) " ($it)" else "" } }
            rows += Row("Clients", (listOf("$total total") + one).joinToString(DOT), null)
        }
        return rows to worst(*rows.map { it.status }.toTypedArray())
    }

    /** -> (summary row, one row per radio), same columns as the prototype's debugmyap. */
    fun apLogRows(wlcName: String, vendor: String, f: Facts, rows: List<Row>, overall: String?, link: Link?,
                  network: String? = null, tabletIp: String? = null): List<Facts> {
        val (site, siteType) = parseWlcName(wlcName)
        val status = rows.associate { it.label to it.status }
        val base = facts("site" to site, "site_type" to siteType, "wlc_name" to wlcName, "wlc_vendor" to vendor,
            "ap_name" to f["ap"], "ap_number" to Parse.apNumber(f.str("ap")))
        if (network != null || tabletIp != null) { base["network"] = network; base["tablet_ip"] = tabletIp }
        @Suppress("UNCHECKED_CAST") val ev = (f["rebootstraps"] as List<List<String>>?) ?: emptyList()
        val missing = f.list("missing")
        val summary: Facts = LinkedHashMap(base)
        summary.putAll(facts(
            "ap_status" to overall,
            "ap_issues" to rows.count { it.status == "WARN" || it.status == "FAIL" },
            "ap_state" to f["state"], "ap_model" to f["model"], "ap_serial" to f["serial"],
            "ap_ip" to f["ip"], "ap_location" to f["location"], "ap_version" to f["version"],
            "ap_uptime_s" to f["uptime_s"], "ap_capwap_uptime_s" to f["capwap_uptime_s"],
            "ap_reboots" to f["reboots"], "ap_crash" to f["crash"],
            "ap_rebootstraps" to if ("rebootstraps" in f) ev.size else null,
            "ap_last_rebootstrap" to ev.lastOrNull()?.let { "${it[0]} ${it[1]}" },
            "ap_cpu_pct" to f["cpu_pct"], "ap_cpu_peak_pct" to f["cpu_peak_pct"], "ap_mem_pct" to f["mem_pct"],
            "poe_type" to f["poe_type"], "poe_watts" to f["poe_watts"], "poe_status" to status["PoE"],
            "link_status" to link?.status, "link_mbps" to link?.mbps, "link_duplex" to link?.duplex,
            "ap_clients" to if ("client_rows" in f) f.maps("client_rows")?.size else null,
            "ap_clients_by_ssid" to (f.maps("_ssids") ?: emptyList()).joinToString(";") { "${it["ssid"]}:${it["clients"]}" }.ifEmpty { null },
            "rf_tag" to f["rf_tag"],
            "error" to if (!missing.isNullOrEmpty()) "couldn't read: " + missing.joinToString(",") else null,
        ))
        val radios = (f.maps("radios") ?: emptyList()).map { r ->
            val state = if (!((r["enabled"] as Boolean?) ?: true)) "OFF" else if ((r["up"] as Boolean?) ?: true) "UP" else "DOWN"
            LinkedHashMap(base).apply {
                putAll(facts(
                    "band" to r["band"], "channel" to if (state != "OFF") r["channel"] else null,
                    "radio_state" to state, "radio_status" to r["_status"],
                    "radio_width_mhz" to if (state != "OFF") r["width"] else null,
                    "tx_power_dbm" to r["tx_dbm"], "eirp_dbm" to r["eirp_dbm"],
                    "chan_util_pct" to r["util_pct"], "noise_dbm" to r["noise_dbm"],
                    "interference_pct" to r["interf_pct"], "radio_clients" to r["clients"],
                    "radio_neighbors" to r["neighbors"], "radio_cochannel" to r["cochannel"],
                    "radar_changes" to r["radar_changes"],
                ))
            }
        }
        return listOf(summary) + radios
    }

    // ============================================================================== Debug my WLC
    /**
     * Controller facts -> rows + overall. [baseline] (Cisco only lists joined APs) returns the most APs ever seen
     * on this controller after recording [joined]; reset = take today's count as normal.
     */
    fun evaluateWlc(f: Facts, wlcName: String, baseline: (String, Int, Boolean) -> Int, resetBaseline: Boolean = false): Pair<List<Row>, String?> {
        val L = HealthLimits
        val rows = mutableListOf<Row>()
        val ignore = L.ignoreAps.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        @Suppress("UNCHECKED_CAST") val downAll = f["aps_down_names"] as List<String>?
        f["_max_seen"] = null
        val down: List<String>?
        val ignored: List<String>
        val total: Int?
        val missingCount: Int?
        if (downAll != null) {                                          // Aruba: down APs listed by name
            ignored = downAll.filter { n -> ignore.any { globMatch(n, it) } }
            down = downAll.filter { it !in ignored }
            total = (f.int("aps_total") ?: 0) - ignored.size
            missingCount = down.size
        } else if (f["aps_joined"] != null) {                           // Cisco: compare with most seen
            val best = baseline(wlcName, f.int("aps_joined")!!, resetBaseline)
            f["_max_seen"] = best
            ignored = emptyList()
            @Suppress("UNCHECKED_CAST")
            down = ((f["aps_notreg_names"] as List<String>?) ?: emptyList()).toList()
            total = best
            missingCount = maxOf(0, best - f.int("aps_joined")!!)
        } else {
            ignored = emptyList(); down = null; total = f.int("aps_total"); missingCount = null
        }
        f["_down"] = down; f["_ignored"] = ignored; f["_total"] = total; f["_missing"] = missingCount
        val downPct = if (missingCount != null && total != null && total != 0) pyRound(100.0 * missingCount / total, 1) else null
        f["_down_pct"] = downPct

        val up = f.num("uptime_s")
        rows += Row("Uptime", fmtUptime(up) + (f.str("reboot_cause")?.takeIf { it.isNotEmpty() }?.let { "${DOT}last reboot: $it" } ?: ""),
            if (up == null) null else if (up.toDouble() < L.wlcUptimeWarnHours * 3600) "WARN" else "PASS")
        rows += Row("CPU", f.num("cpu_pct")?.let { "${fmt0(it)}%" } ?: "?",
            f.str("cpu_level")?.ifEmpty { null } ?: level(f.num("cpu_pct"), L.wlcCpuWarnPct, L.wlcCpuFailPct))
        rows += Row("Memory", f.num("mem_pct")?.let { "${fmt0(it)}%" } ?: "?",
            f.str("mem_level")?.ifEmpty { null } ?: level(f.num("mem_pct"), L.wlcMemWarnPct, L.wlcMemFailPct))
        if ("storage_pct" in f || "storage" in (f.list("missing") ?: emptyList<Any>())) {
            rows += Row("Storage", f.num("storage_pct")?.let { "$it% (${f["storage_mount"] ?: ""})" } ?: "?",
                level(f.num("storage_pct"), L.storageWarnPct, L.storageFailPct))
        }
        val ups = f.maps("uplinks")
        when {
            ups == null -> rows += Row("Uplink", "?", null)
            ups.isEmpty() -> rows += Row("Uplink", "no port up", "FAIL")
            else -> rows += Row("Uplink", ups.joinToString(", ") { u ->
                "${u["port"]} ${fmtMbps(u.num("mbps"))} ${u["duplex"]}".trim() + if (truthy(u["error"])) " ERR ${u["error"]}" else ""
            }, if (ups.any { truthy(it["error"]) }) "FAIL" else "PASS")
        }
        val maxSeen = f.int("_max_seen")
        if (maxSeen != null) {
            val j = f.int("aps_joined")!!
            val miss = missingCount ?: 0
            var txt = "$j joined" + if (miss != 0) "$DOT$miss missing vs $maxSeen seen before (${fmtG(downPct)}%)" else "${DOT}most seen $maxSeen"
            if (!down.isNullOrEmpty()) txt += "$DOT${down.size} not registered"
            var s = level(downPct, L.apsDownWarnPct, L.apsDownFailPct)
            if (!down.isNullOrEmpty() && s == "PASS") s = "WARN"
            rows += Row("APs", txt, s)
        } else if (down != null && total != null && total != 0) {
            var txt = "${total - down.size}/$total up$DOT${down.size} down (${fmtG(downPct)}%)"
            if (ignored.isNotEmpty()) txt += "$DOT${ignored.size} ignored"
            rows += Row("APs", txt, level(downPct, L.apsDownWarnPct, L.apsDownFailPct))
        } else {
            rows += Row("APs", "?", null)
        }
        if (f["radios_5_total"] != null) {
            val parts = mutableListOf<String>()
            var s: String? = "PASS"
            for ((key, name) in listOf("5" to "5 GHz", "6" to "6 GHz", "24" to "2.4 GHz")) {
                val tot = f.int("radios_${key}_total")
                val u = f.int("radios_${key}_up")
                if (tot != null && tot != 0) {
                    parts += "$name $u/$tot on"
                    if (key != "24" && (u ?: 0) < tot) s = "WARN"          // 2.4 GHz radios are often off by design (RRM)
                }
            }
            rows += Row("Radios", parts.joinToString(DOT).ifEmpty { "?" }, s)
        }
        if (f["clients_unique"] != null) rows += Row("Clients", "${f["clients_unique"]} unique (${f["clients_total"] ?: "?"} sessions)", null)
        else if (f["clients_total"] != null) rows += Row("Clients", "${f["clients_total"]}", null)

        val hw = mutableListOf<String>()
        var hst: String? = null
        f.num("temp_cpu_c")?.let {
            hw += "CPU ${fmt0(it)}°C"
            hst = if ((f.dbl("temp_max_c") ?: 0.0) >= L.tempWarnC) "WARN" else "PASS"
        }
        if (truthy(f["psu_total"])) {
            hw += "PSU ${f["psu_present"]} of ${f["psu_total"]}"
            if (f["psu_fault"] == true) hst = "FAIL"
        }
        if (truthy(f["fans"])) {
            hw += "${f["fans"]} fans " + if (f["fan_fault"] == true) "FAULT" else "OK"
            if (f["fan_fault"] == true) hst = if (hst == "FAIL") "FAIL" else "WARN"
        }
        if (listOf("alarms_critical", "alarms_major", "alarms_minor").any { it in f }) {
            val crit = f.int("alarms_critical") ?: 0
            val maj = f.int("alarms_major") ?: 0
            val mnr = f.int("alarms_minor") ?: 0
            hw += if (crit == 0 && maj == 0 && mnr == 0) "no alarms" else "alarms $crit critical/$maj major/$mnr minor"
            val ast = if (crit != 0 || maj != 0) "FAIL" else if (mnr != 0) "WARN" else "PASS"
            if (RANK.getValue(ast) > RANK.getValue(hst)) hst = ast
        }
        @Suppress("UNCHECKED_CAST") val abnormal = f["temp_abnormal"] as List<String>?
        if (!abnormal.isNullOrEmpty()) {
            hw += "temp not normal: " + abnormal.joinToString(", ")
            hst = if (hst == "FAIL") "FAIL" else "WARN"
        }
        rows += Row("Hardware", hw.joinToString(DOT).ifEmpty { "?" }, hst)
        if ("controller_crash" in f) {
            var txt = if (f["controller_crash"] == true) "controller crash on record" else "none on controller"
            if (f["ap_crash_info"] == true) txt += "${DOT}AP crash info present"
            rows += Row("Crashes", txt, if (f["controller_crash"] == true) "WARN" else "PASS")
        }
        return rows to worst(*rows.map { it.status }.toTypedArray())
    }

    /** -> (summary row, one row per SSID), same columns as the prototype's debugmywlc. */
    fun wlcLogRows(wlcName: String, vendor: String, f: Facts, rows: List<Row>, overall: String?): List<Facts> {
        val (site, siteType) = parseWlcName(wlcName)
        val status = rows.associate { it.label.lowercase() to it.status }
        val base = facts("site" to site, "site_type" to siteType, "wlc_name" to wlcName, "wlc_vendor" to vendor)
        val ups = f.maps("uplinks") ?: emptyList()
        val missing = f.list("missing")
        @Suppress("UNCHECKED_CAST") val busiest = (f["busiest_aps"] as List<List<Any?>>?) ?: emptyList()
        val summary: Facts = LinkedHashMap(base)
        summary.putAll(facts(
            "wlc_status" to overall, "wlc_model" to f["model"], "wlc_version" to f["version"],
            "wlc_uptime_s" to f["uptime_s"], "reboot_cause" to f["reboot_cause"],
            "cpu_pct" to f["cpu_pct"], "mem_pct" to f["mem_pct"], "storage_pct" to f["storage_pct"],
            "uplink_mbps" to if (ups.isNotEmpty()) ups.maxOf { it.int("mbps") ?: 0 } else null,
            "uplink_status" to status["uplink"],
            "aps_total" to f["_total"], "aps_down" to f["_missing"],
            "aps_down_pct" to f["_down_pct"], "aps_ignored" to ((f["_ignored"] as List<*>?)?.size?.takeIf { it != 0 }),
            "aps_down_list" to ((f["_down"] as List<*>?) ?: emptyList<Any>()).joinToString(";").take(400).ifEmpty { null },
            "clients_unique" to f["clients_unique"], "clients_total" to f["clients_total"],
            "temp_cpu_c" to f["temp_cpu_c"], "temp_max_c" to f["temp_max_c"],
            "psu_present" to f["psu_present"], "psu_total" to f["psu_total"],
            "fans_ok" to if ("fan_fault" in f) f["fan_fault"] != true else null,
            "controller_crash" to f["controller_crash"],
            "busiest_aps" to busiest.joinToString(";") { "${it[0]}:${it[1]}" }.ifEmpty { null },
            "aps_joined" to f["aps_joined"], "aps_max_seen" to f["_max_seen"],
            "aps_missing" to if (f["_max_seen"] != null) f["_missing"] else null,
            "radios_5_up" to f["radios_5_up"], "radios_5_total" to f["radios_5_total"],
            "radios_24_up" to f["radios_24_up"], "radios_24_total" to f["radios_24_total"],
            "radios_6_up" to f["radios_6_up"]?.takeIf { truthy(it) }, "radios_6_total" to f["radios_6_total"]?.takeIf { truthy(it) },
            "alarms_critical" to f["alarms_critical"], "alarms_major" to f["alarms_major"],
            "alarms_minor" to f["alarms_minor"],
            "error" to if (!missing.isNullOrEmpty()) "couldn't read: " + missing.joinToString(",") else null,
        ))
        val perSsid = (f.maps("ssids") ?: emptyList()).map { s ->
            @Suppress("UNCHECKED_CAST") val b = (s["bands"] as Map<String, Int>?) ?: emptyMap()
            LinkedHashMap(base).apply {
                putAll(facts("ssid" to s["ssid"], "network" to s["ssid"], "clients" to s["clients"], "ssid_aps" to s["aps"],
                    "clients_5" to b["5"], "clients_24" to b["2.4"], "clients_6" to b["6"]))
            }
        }
        return listOf(summary) + perSsid
    }
}
