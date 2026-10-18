package io.github.filmonm.whatsmyap.wlc

/**
 * Aruba 8.x controller health (port of aruba8_health.py, confirmed on 8.10.0.15).
 * Each parser returns {} when it can't read its output, so one odd command never sinks the check.
 */
object ArubaHealth {

    val COMMANDS = linkedMapOf(
        "cpu" to "show cpu",
        "memory" to "show memory",
        "storage" to "show storage",
        "ports" to "show port status",
        "ap_total" to "show ap database | include Tot",
        "ap_down" to "show ap database status down",
        "users" to "show user-table summary",
        "essid" to "show ap essid",
        "inventory" to "show inventory",
        "switchinfo" to "show switchinfo",
    )

    fun cpu(text: String): Facts =
        group("""idle\s+([\d.]+)%""", text)?.let { facts("cpu_pct" to pyRound(100 - it.toDouble(), 1)) } ?: facts()

    fun memory(text: String): Facts {
        val m = search("""total:\s*(\d+),\s*used:\s*(\d+)""", text) ?: return facts()
        val total = m.groupValues[1].toLong()
        val used = m.groupValues[2].toLong()
        return facts(
            "mem_pct" to if (total != 0L) pyRound(100.0 * used / total, 1) else null,
            "mem_total_gb" to pyRound(total / 1024.0 / 1024.0, 1),
        )
    }

    /** Highest Use% among the controller's own disks (/flash, /mnt/...). */
    fun storage(text: String): Facts {
        var worst: Pair<Int, String>? = null
        for (line in text.lines()) {
            val m = search("""(\d+)%\s+(/\S*)\s*$""", line) ?: continue
            val mount = m.groupValues[2]
            if (mount.startsWith("/flash") || mount.startsWith("/mnt")) {
                val pct = m.groupValues[1].toInt()
                if (worst == null || pct > worst.first) worst = pct to mount
            }
        }
        return worst?.let { facts("storage_pct" to it.first, "storage_mount" to it.second) } ?: facts()
    }

    private fun low(r: Map<String, String>) = r.mapKeys { it.key.lowercase() }

    /** Ports that are up = the uplinks; any PortError is flagged. */
    fun ports(text: String): Facts {
        val up = mutableListOf<Facts>()
        for (rows in Parse.dashTable(text)) for (r in rows) {
            val l = low(r)
            if ((l["operstate"] ?: "").lowercase() != "up") continue
            val err = l["porterror"] ?: "-"
            up += facts("port" to (l["slot-port"] ?: ""), "mbps" to Parse.speedToMbps(l["speed"] ?: ""),
                "duplex" to (l["duplex"] ?: ""), "error" to if (err == "" || err == "-") "" else err)
        }
        if (up.isEmpty() && "OperState" !in text) return facts()
        return facts("uplinks" to up)
    }

    fun apTotal(text: String): Facts = group("""Total APs:\s*(\d+)""", text)?.let { facts("aps_total" to it.toInt()) } ?: facts()

    fun apDown(text: String): Facts {
        val names = mutableListOf<String>()
        for (rows in Parse.dashTable(text)) for (r in rows) {
            val l = low(r)
            if (!l["name"].isNullOrEmpty() && (l["status"] ?: "").lowercase().startsWith("down")) names += l.getValue("name")
        }
        if (names.isEmpty() && "Total APs" !in text) return facts()
        return facts("aps_down_names" to names)
    }

    fun users(text: String): Facts {
        val m = search("""Unique Users:\s*(\d+)\s+Total Users:\s*(\d+)""", text) ?: return facts()
        return facts("clients_unique" to m.groupValues[1].toInt(), "clients_total" to m.groupValues[2].toInt())
    }

    fun essid(text: String): Facts {
        val out = mutableListOf<Facts>()
        for (rows in Parse.dashTable(text)) for (r in rows) {
            val l = low(r)
            if (!l["essid"].isNullOrEmpty() && l["clients"].isDigits()) {
                out += facts("ssid" to l["essid"], "clients" to l.getValue("clients").toInt(),
                    "aps" to if (l["aps"].isDigits()) l.getValue("aps").toInt() else null)
            }
        }
        return if (out.isNotEmpty()) facts("ssids" to out) else facts()
    }

    fun inventory(text: String): Facts {
        val res = facts()
        val temps = LinkedHashMap<String, Double>()
        rx("""^\s*:\s*([A-Z0-9_]+)\s+([\d.]+)\s*C\s*$""", "m").findAll(text).forEach { temps[it.groupValues[1].trim()] = it.groupValues[2].toDouble() }
        if (temps.isNotEmpty()) {
            res["temp_cpu_c"] = temps["CPU"]
            res["temp_max_c"] = temps.values.max()
        }
        group("""SC Model\s*:\s*(\S+)""", text)?.let { res["model"] = it }
        // power supplies: "Power Supply N :" then ": Status  Present|Absent" and ": 12V or AC  OK"
        val psus = mutableListOf<Facts>()
        var cur: Facts? = null
        for (line in text.lines()) {
            val h = match("""Power Supply (\d+)\s*:""", line)
            if (h != null) {
                cur = facts("id" to h.groupValues[1].toInt(), "status" to "", "power" to "")
                psus += cur
                continue
            }
            if (cur != null) {
                val s = match("""\s*:\s*Status\s+(\S+)""", line)
                val p = match("""\s*:\s*12V or AC\s+(\S+)""", line)
                when {
                    s != null -> cur["status"] = s.groupValues[1]
                    p != null -> cur["power"] = p.groupValues[1]
                    !line.startsWith(" ") && line.isNotBlank() -> cur = null
                }
            }
        }
        if (psus.isNotEmpty()) {
            val present = psus.filter { (it.str("status") ?: "").lowercase() == "present" }
            res["psu_total"] = psus.size
            res["psu_present"] = present.size
            res["psu_fault"] = present.any { val p = it.str("power") ?: ""; p.isNotEmpty() && p.uppercase() != "OK" }
        }
        val fans = rx("""FAN\s*\d+\s+(\d+)\s*rpm""").findAll(text).map { it.groupValues[1].toInt() }.toList()
        if (fans.isNotEmpty()) {
            res["fans"] = fans.size
            res["fan_fault"] = fans.any { it == 0 } || search("""FAN.*(fail|fault)""", text, "i") != null
        }
        return res
    }

    private fun uptimeSeconds(s: String): Int {
        val units = mapOf("day" to 86400, "hour" to 3600, "minute" to 60, "second" to 1)
        return rx("""(\d+)\s*(day|hour|minute|second)""").findAll(s).sumOf { it.groupValues[1].toInt() * units.getValue(it.groupValues[2]) }
    }

    fun switchinfo(text: String): Facts {
        val res = facts()
        group("""uptime is (.+)$""", text, flags = "m")?.let { res["uptime_s"] = uptimeSeconds(it) }
        group("""Reboot Cause:\s*(.+?)\s*(\(|$)""", text, flags = "m")?.let { res["reboot_cause"] = it.trim() }
        group("""MODEL:\s*([^)]+)\)""", text)?.let { res["model"] = it.trim() }
        group("""Version\s+(\d+(?:\.\d+)+)""", text)?.let { res["version"] = it }
        if ("No controller crash information" in text) res["controller_crash"] = false
        else if (search("""controller crash information available""", text, "i") != null) res["controller_crash"] = true
        res["ap_crash_info"] = search("""AP Crash information available""", text, "i") != null
        return res
    }

    private val PARSERS: Map<String, (String) -> Facts> = mapOf(
        "cpu" to ::cpu, "memory" to ::memory, "storage" to ::storage, "ports" to ::ports,
        "ap_total" to ::apTotal, "ap_down" to ::apDown, "users" to ::users, "essid" to ::essid,
        "inventory" to ::inventory, "switchinfo" to ::switchinfo,
    )

    fun collect(run: (String) -> String?): Facts {
        val f = facts()
        val missing = mutableListOf<String>()
        for ((key, cmd) in COMMANDS) {
            val got = runCatching { run(cmd)?.takeIf { it.isNotEmpty() }?.let { PARSERS.getValue(key)(it) } ?: facts() }.getOrElse { facts() }
            if (got.isEmpty()) missing += key
            // inventory's model ("Aruba9240-US") is less readable than switchinfo's; keep the first
            if ("model" in got && "model" in f) got.remove("model")
            f.putAll(got)
        }
        f["missing"] = missing
        return f
    }
}
