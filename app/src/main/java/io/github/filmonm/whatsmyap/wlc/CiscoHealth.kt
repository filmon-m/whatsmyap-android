package io.github.filmonm.whatsmyap.wlc

/**
 * Cisco Catalyst 9800 controller health (port of cisco9800_health.py, confirmed on 17.9.6).
 *
 * - A 9800 lists only JOINED APs; down APs simply aren't there. Debug my WLC compares the joined
 *   count with the highest count seen before on this controller.
 * - 'Down' in show wireless summary counts RADIOS. 2.4 GHz radios are often off on purpose (RRM),
 *   so only 5/6 GHz radios down are flagged.
 * - The Control Processor figure from show platform resources is the CPU that matters.
 */
object CiscoHealth {

    val COMMANDS = linkedMapOf(
        "version" to "show version | include uptime|Version|reload reason",
        "resources" to "show platform resources",
        "ap_count" to "show ap summary | include Number of APs",
        "ap_notreg" to "show ap summary | exclude Registered",
        "wireless" to "show wireless summary",
        "wlan" to "show wlan summary",
        "env" to "show environment summary",
        "uplink" to "show interfaces status | include connected",
        "clients" to "show wireless client summary",
    )

    private val STATE = mapOf("H" to "PASS", "W" to "WARN", "C" to "FAIL")

    fun uptimeSeconds(s: String): Int {
        val units = mapOf("year" to 31536000, "week" to 604800, "day" to 86400, "hour" to 3600, "minute" to 60, "second" to 1)
        return rx("""(\d+)\s*(year|week|day|hour|minute|second)""").findAll(s)
            .sumOf { it.groupValues[1].toInt() * units.getValue(it.groupValues[2]) }
    }

    fun version(text: String): Facts {
        val res = facts()
        group("""uptime is (.+)$""", text, flags = "m")?.let { res["uptime_s"] = uptimeSeconds(it) }
        group("""Cisco IOS XE Software, Version\s+(\S+)""", text)?.let { res["version"] = it }
        res["model"] = group("""\((C\d{4}[\w-]*)_IOSXE""", text) ?: "C9800"
        group("""[Rr]eload [Rr]eason:\s*(.+)$""", text, flags = "m")?.let { res["reboot_cause"] = it.trim() }
        return res
    }

    fun resources(text: String): Facts {
        val res = facts()
        search("""Control Processor\s+([\d.]+)%.*?\s([HWC])\s*$""", text, "m")?.let {
            res["cpu_pct"] = it.groupValues[1].toDouble(); res["cpu_level"] = STATE[it.groupValues[2]]
        }
        // first DRAM line after RP0 = control-plane memory
        search("""RP0.*?\n\s*Control Processor.*?\n\s*DRAM\s+\S+?\(([\d.]+)%\).*?\s([HWC])\s*$""", text, "sm")?.let {
            res["mem_pct"] = it.groupValues[1].toDouble(); res["mem_level"] = STATE[it.groupValues[2]]
        }
        return res
    }

    fun apCount(text: String): Facts =
        group("""Number of APs:\s*(\d+)""", text)?.let { facts("aps_joined" to it.toInt()) } ?: facts()

    /** APs listed in a state other than Registered (e.g. Downloading). Usually empty. */
    fun apNotreg(text: String): Facts {
        if ("AP Name" !in text) return facts()
        val names = mutableListOf<String>()
        var after = false
        for (line in text.lines()) {
            if (fullmatch("-{20,}", line.trim())) { after = true; continue }
            if (after && line.isNotBlank()) names += line.words()[0]
        }
        return facts("aps_notreg_names" to names)
    }

    fun wireless(text: String): Facts {
        val res = facts()
        for ((band, key) in listOf("2.4GHz" to "24", "5GHz" to "5", "6GHz" to "6")) {
            search("""^Client Serving\(${Regex.escape(band)}\)\s+(\d+)\s+(\d+)\s+(\d+)""", text, "m")?.let {
                res["radios_${key}_total"] = it.groupValues[1].toInt()
                res["radios_${key}_up"] = it.groupValues[2].toInt()
            }
        }
        group("""Total Clients\s*:\s*(\d+)""", text)?.let { res["clients_total"] = it.toInt() }
        return res
    }

    fun wlan(text: String): Facts {
        val ssids = mutableListOf<Facts>()
        for (line in text.lines()) {
            match("""\s*(\d+)\s+\S+\s+(\S+)\s+(UP|DOWN)\b""", line)?.let {
                ssids += facts("id" to it.groupValues[1].toInt(), "ssid" to it.groupValues[2], "up" to (it.groupValues[3] == "UP"))
            }
        }
        return if (ssids.isNotEmpty()) facts("wlans" to ssids) else facts()
    }

    fun env(text: String): Facts {
        val res = facts()
        for (kind in listOf("Critical", "Major", "Minor")) {
            group("""Number of $kind alarms:\s*(\d+)""", text)?.let { res["alarms_${kind.lowercase()}"] = it.toInt() }
        }
        val temps = LinkedHashMap<String, Pair<Int, String>>()
        rx("""Temp:\s*(.+?)\s{2,}(\S+)\s+(\d+)\s+Celsius""").findAll(text).forEach {
            temps[it.groupValues[1].trim()] = it.groupValues[3].toInt() to it.groupValues[2]
        }
        if (temps.isNotEmpty()) {
            val cpu = temps.entries.firstOrNull { "cpu" in it.key.lowercase() }?.value
            res["temp_cpu_c"] = cpu?.first
            res["temp_max_c"] = temps.values.maxOf { it.first }
            res["temp_abnormal"] = temps.filter { it.value.second.lowercase() != "normal" }.keys.toList()
        }
        return res
    }

    fun uplink(text: String): Facts {
        val ups = mutableListOf<Facts>()
        for (line in text.lines()) {
            if ("connected" !in line) continue
            val parts = line.words()
            val port = parts[0]
            val speed = parts.firstOrNull { fullmatch("""a?-?\d+(\.\d+)?[GM]?""", it) && it != "full" && it != "half" } ?: ""
            val duplex = parts.firstOrNull { it.trimStart('a', '-') in setOf("full", "half") } ?: ""
            val mbps = Parse.speedToMbps(speed.replace("a-", ""))
            ups += facts("port" to port, "mbps" to mbps, "duplex" to duplex.replace("a-", "").pyCapitalize(), "error" to "")
        }
        if (ups.isEmpty() && "connected" !in text) return facts()
        return facts("uplinks" to ups)
    }

    /** 11ax(5) -> 5, 11n(2.4) -> 2.4, 11ac/11a -> 5 (5 GHz only), 11g/11b -> 2.4. */
    fun protoBand(p: String): String {
        group("""\((2\.4|5|6)\)""", p)?.let { return it }
        return when (p.lowercase()) {
            "11ac", "11a" -> "5"
            "11g", "11b" -> "2.4"
            else -> ""
        }
    }

    fun clients(text: String): Facts {
        val rows = mutableListOf<Facts>()
        for (line in text.lines()) {
            match("""([0-9a-f]{4}\.[0-9a-f]{4}\.[0-9a-f]{4})\s+(\S+)\s+WLAN\s+(\d+)\s+(.+?)\s{2,}(\S+)""", line, "i")?.let {
                rows += facts("ap" to it.groupValues[2], "wlan" to it.groupValues[3].toInt(), "state" to it.groupValues[4].trim(),
                    "band" to protoBand(it.groupValues[5]))
            }
        }
        if (rows.isEmpty() && "Number of Clients" !in text) return facts()
        return facts("client_rows" to rows)
    }

    /** Client rows -> per-SSID counts with band split, plus the busiest APs. */
    fun summarizeClients(f: Facts) {
        if (!f.containsKey("client_rows")) return
        val rows = f.remove("client_rows") as List<*>? ?: return
        val names = (f.maps("wlans") ?: emptyList()).associate { it["id"] as Int to it["ssid"] as String }
        val per = LinkedHashMap<String, Facts>()
        val perAp = LinkedHashMap<String, Int>()
        for (r in rows.map { @Suppress("UNCHECKED_CAST") (it as Facts) }) {
            val ssid = names[r["wlan"] as Int] ?: "WLAN ${r["wlan"]}"
            val s = per.getOrPut(ssid) { facts("ssid" to ssid, "clients" to 0, "aps" to null, "bands" to linkedMapOf<String, Int>()) }
            s["clients"] = (s["clients"] as Int) + 1
            val band = r["band"] as String
            @Suppress("UNCHECKED_CAST") val bands = s["bands"] as MutableMap<String, Int>
            if (band.isNotEmpty()) bands[band] = (bands[band] ?: 0) + 1
            val ap = r["ap"] as String
            perAp[ap] = (perAp[ap] ?: 0) + 1
        }
        for (w in f.maps("wlans") ?: emptyList()) {                // SSIDs with nobody on them still show
            val ssid = w["ssid"] as String
            per.getOrPut(ssid) { facts("ssid" to ssid, "clients" to 0, "aps" to null, "bands" to linkedMapOf<String, Int>()) }
        }
        f["ssids"] = per.values.toList()
        f["busiest_aps"] = perAp.entries.sortedBy { -it.value }.take(5).map { listOf(it.key, it.value) }
        f["clients_not_run"] = rows.count { (it as Map<*, *>)["state"].toString().lowercase() != "run" }
    }

    private val PARSERS: Map<String, (String) -> Facts> = mapOf(
        "version" to ::version, "resources" to ::resources, "ap_count" to ::apCount, "ap_notreg" to ::apNotreg,
        "wireless" to ::wireless, "wlan" to ::wlan, "env" to ::env, "uplink" to ::uplink, "clients" to ::clients,
    )

    fun collect(run: (String) -> String?): Facts {
        val f = facts()
        val missing = mutableListOf<String>()
        for ((key, cmd) in COMMANDS) {
            val got = runCatching { run(cmd)?.takeIf { it.isNotEmpty() }?.let { PARSERS.getValue(key)(it) } ?: facts() }.getOrElse { facts() }
            if (got.isEmpty()) missing += key
            f.putAll(got)
        }
        summarizeClients(f)
        f["missing"] = missing
        return f
    }
}
