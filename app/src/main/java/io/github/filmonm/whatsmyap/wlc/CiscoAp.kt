package io.github.filmonm.whatsmyap.wlc

/**
 * Cisco 9800 - health of ONE AP (port of cisco9800_ap.py, confirmed on 17.9.6 / C9115AXI).
 *
 * show ap name <ap> config general           model, uptime, join time, PoE mode, IP, tags
 * show ap name <ap> config dot11 5ghz        admin/oper state, channel, width, Tx power (per band)
 * show ap name <ap> auto-rf dot11 5ghz       utilization, noise, interference, neighbours, RRM profiles
 * show wlan summary                          WLAN id -> SSID name
 * show wireless client summary | include <ap>  clients on this AP
 * The 6 GHz commands only run on APs with 3 radio slots.
 */
object CiscoAp {

    private val BANDS = mapOf("2.4" to "24ghz", "5" to "5ghz", "6" to "6ghz")

    fun general(text: String): Facts {
        if ("Cisco AP Name" !in text && "AP Model" !in text) return facts()
        val kv = Parse.parseKv(text)
        val res = facts(
            "state" to kv["operation state"], "model" to kv["ap model"], "serial" to kv["ap serial number"],
            "ip" to kv["ip address"], "location" to kv["cisco ap location"], "version" to kv["software version"],
            "poe_type" to kv["power type/mode"], "joined" to kv["join date and time"],
            "rf_tag" to kv["rf tag name"], "policy_tag" to kv["policy tag name"], "site_tag" to kv["site tag name"],
            "mode" to kv["ap mode"],
        )
        kv["ap up time"]?.takeIf { it.isNotEmpty() }?.let { res["uptime_s"] = CiscoHealth.uptimeSeconds(it) }
        kv["ap capwap up time"]?.takeIf { it.isNotEmpty() }?.let { res["capwap_uptime_s"] = CiscoHealth.uptimeSeconds(it) }
        if (kv["number of slots"].isDigits()) res["slots"] = kv.getValue("number of slots").toInt()
        val pt = (res.str("poe_type") ?: "").lowercase()
        if (pt.isNotEmpty()) res["poe_restricted"] = "full power" !in pt
        return res.compact()
    }

    /** config dot11 <band> -> the radio slot's state, channel, width and Tx power. */
    fun dot11(text: String): Facts {
        val i = text.indexOf("Attributes for Slot")
        if (i < 0) return facts()
        val body = text.substring(i)
        val kv = Parse.parseKv(body)
        val r = facts(
            "admin" to (kv["administrative state"] ?: ""), "oper" to (kv["operation state"] ?: ""),
            "role" to (kv["radio role"] ?: ""), "channel" to (kv["current channel"] ?: ""),
            "radio_type" to (kv["radio type"] ?: ""),
        )
        r["width"] = group("""\d+""", kv["channel width"] ?: "", 0)?.toInt()
        val lvl = (kv["current tx power level"] ?: "").trim()
        val m = if (lvl.isDigits()) search("""Tx Power Level ${Regex.escape(lvl)}\s*:\s*(-?\d+)\s*dBm""", body) else null
        r["tx_dbm"] = m?.groupValues?.get(1)?.toInt()
        r["tx_level"] = if (lvl.isDigits()) lvl.toInt() else null
        r["enabled"] = (r.str("admin") ?: "").lowercase() == "enabled"
        r["up"] = (r.str("oper") ?: "").lowercase() == "up"
        return r
    }

    /** 20 MHz channels a radio occupies (to spot co-channel neighbours). */
    fun span(chIn: Any?, width: Int?, band: String?): Set<Int> {
        val ch = when (chIn) {
            is Int -> chIn
            is String -> chIn.trim().toIntOrNull() ?: return emptySet()
            else -> return emptySet()
        }
        if (band != "5" || width == null || width <= 20) return setOf(ch)
        val n = width / 20
        for ((start, end) in listOf(36 to 64, 100 to 144, 149 to 165)) {
            if (ch in start..end) {
                val i = (ch - start) / 4 / n * n
                return (0 until n).map { start + 4 * (i + it) }.toSet()
            }
        }
        return setOf(ch)
    }

    fun autoRf(text: String, channel: String? = null, width: Int? = null, band: String? = null): Facts {
        if ("Load Information" !in text && "Noise Information" !in text) return facts()
        val r = facts()
        for ((key, pat) in listOf(
            "util_pct" to """Channel Utilization\s*:\s*(\d+)%""", "rx_util_pct" to """Receive Utilization\s*:\s*(\d+)%""",
            "tx_util_pct" to """Transmit Utilization\s*:\s*(\d+)%""", "clients" to """Attached Clients\s*:\s*(\d+)""",
            "radar_changes" to """Channel changes due to radar\s*:\s*(\d+)""",
            "dca_changes" to """Channel Change Count\s*:\s*(\d+)""",
        )) {
            group(pat, text, flags = "m")?.let { r[key] = it.toInt() }
        }
        r["profiles_failed"] = rx("""(Noise|Interference|Load|Coverage) Profile\s*:\s*Failed""").findAll(text).map { it.groupValues[1] }.toList()
        if (!channel.isNullOrEmpty()) {
            val a = text.indexOf("Noise Information")
            val b = text.indexOf("Interference Information")
            val noise = pySlice(text, a, b)
            search("""Channel\s+${Regex.escape(channel)}\s*:\s*(-?\d+)\s*dBm\s*$""", noise, "m")?.let { r["noise_dbm"] = it.groupValues[1].toInt() }
            search("""Channel\s+${Regex.escape(channel)}\s*:\s*(-?\d+)\s*dBm\s*@\s*(\d+)%\s*busy""", text)?.let { r["interf_pct"] = it.groupValues[2].toInt() }
        }
        val near = rx("""AP \S+ slot \d+\s*:\s*(-?\d+)\s*dBm on \(\s*(\d+),\s*(\d+)\s*MHz\)""").findAll(text)
            .map { Triple(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }.toList()
        if (near.isNotEmpty() || "Nearby APs" in text) {
            r["neighbors"] = near.size
            val mine = span(channel, width, band)
            val co = near.filter { (rssi, ch, w) -> span(ch, w, band).intersect(mine).isNotEmpty() && rssi >= -82 }.map { it.first }
            r["cochannel"] = co.size
            r["cochannel_best"] = co.maxOrNull()
        }
        return r
    }

    /** Python text[a:b] where find() may have returned -1. */
    private fun pySlice(s: String, a: Int, b: Int): String {
        fun idx(i: Int) = if (i < 0) maxOf(0, s.length + i) else minOf(i, s.length)
        val x = idx(a); val y = idx(b)
        return if (y <= x) "" else s.substring(x, y)
    }

    fun collect(run: (String) -> String?, ap: String): Facts {
        val f = facts("ap" to ap)
        val missing = mutableListOf<String>()
        fun get(key: String, cmd: String, parser: (String) -> Facts): Facts {
            val got = runCatching { run(cmd)?.takeIf { it.isNotEmpty() }?.let(parser) ?: facts() }.getOrElse { facts() }
            if (got.isEmpty()) missing += key
            return got
        }
        f.putAll(get("general", "show ap name $ap config general", ::general))
        if (!truthy(f["model"]) && !truthy(f["state"])) f["not_found"] = true
        val bands = listOf("5", "2.4") + if ((f.int("slots") ?: 0) >= 3) listOf("6") else emptyList()
        val radios = mutableListOf<Facts>()
        for (band in bands) {
            val r = get("dot11 $band", "show ap name $ap config dot11 ${BANDS[band]}", ::dot11)
            if (r.isEmpty()) continue
            r["band"] = band
            if (r["enabled"] == true) {
                r.putAll(get("auto-rf $band", "show ap name $ap auto-rf dot11 ${BANDS[band]}") {
                    autoRf(it, r.str("channel"), r.int("width"), band)
                })
            }
            radios += r
        }
        f["radios"] = radios
        val names = (get("wlan", "show wlan summary", CiscoHealth::wlan).maps("wlans") ?: emptyList())
            .associate { it["id"] as Int to it["ssid"] as String }
        val text = runCatching { run("show wireless client summary | include $ap") }.getOrNull()
        if (text == null) {
            missing += "clients"
        } else {
            val rows = (CiscoHealth.clients(text + "\nNumber of Clients").maps("client_rows") ?: emptyList()).filter { it["ap"] == ap }
            f["client_rows"] = rows.map { facts("ssid" to (names[it["wlan"] as Int] ?: "WLAN ${it["wlan"]}"), "band" to it["band"]) }
        }
        f["missing"] = missing
        return f
    }
}
