package io.github.filmonm.whatsmyap.data

import java.io.File

/**
 * The unified CSV log (same format as the prototype, so old and new logs mix).
 * Values are written BY COLUMN NAME; a new column is added to the header and older
 * rows get a blank there. One file per month; a survey also gets its own file.
 */
object CsvLog {

    val CORE = listOf(
        "timestamp", "module", "mode", "run_id", "app_version", "site", "site_type", "network",
        "wlc_name", "wlc_vendor", "ap_name", "ap_number", "ssid", "band", "channel", "bssid",
        "tablet_ip", "rssi_tablet_dbm",
    )

    fun order(keys: Collection<String>): List<String> {
        val k = LinkedHashSet(keys)
        return CORE.filter { it in k } + k.filter { it !in CORE }
    }

    fun clean(v: Any?): String = when (v) {
        null -> ""
        is Double -> "%.1f".format(java.util.Locale.US, v)
        is Float -> "%.1f".format(java.util.Locale.US, v)
        is Boolean -> if (v) "True" else "False"
        else -> v.toString()
    }

    fun escape(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun line(values: List<String>) = values.joinToString(",") { escape(it) }

    /** Parse CSV text (quotes, doubled quotes, newlines inside quotes). */
    fun parse(text: String): List<List<String>> {
        val out = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val f = StringBuilder()
        var q = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (q) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { f.append('"'); i++ } else q = false
                } else f.append(c)
            } else when (c) {
                '"' -> q = true
                ',' -> { row.add(f.toString()); f.clear() }
                '\n', '\r' -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row.add(f.toString()); f.clear()
                    out.add(row); row = mutableListOf()
                }
                else -> f.append(c)
            }
            i++
        }
        if (f.isNotEmpty() || row.isNotEmpty()) { row.add(f.toString()); out.add(row) }
        return out
    }

    fun read(file: File): List<Map<String, String>> {
        if (!file.exists()) return emptyList()
        val rows = parse(file.readText())
        if (rows.isEmpty()) return emptyList()
        val header = rows.first()
        return rows.drop(1).filter { it.size > 1 }.map { r -> header.mapIndexed { i, h -> h to (r.getOrNull(i) ?: "") }.toMap() }
    }

    /** Append rows, widening the header if a row brings new columns (rewritten via a temp file). */
    @Synchronized
    fun write(file: File, rows: List<Map<String, Any?>>) {
        file.parentFile?.mkdirs()
        val clean = rows.map { r -> r.mapValues { clean(it.value) } }
        val names = order(clean.flatMap { it.keys })
        if (!file.exists() || file.length() == 0L) {
            val header = order(CORE + names)
            file.writeText(buildString {
                appendLine(line(header))
                clean.forEach { r -> appendLine(line(header.map { r[it] ?: "" })) }
            })
            return
        }
        val existing = parse(file.readText())
        val header = existing.firstOrNull() ?: emptyList()
        val missing = names.filter { it !in header }
        if (missing.isEmpty()) {
            file.appendText(buildString { clean.forEach { r -> appendLine(line(header.map { r[it] ?: "" })) } })
            return
        }
        val newHeader = header + missing
        val tmp = File(file.path + ".tmp")
        tmp.writeText(buildString {
            appendLine(line(newHeader))
            existing.drop(1).filter { it.size > 1 }.forEach { r ->
                appendLine(line(newHeader.mapIndexed { i, _ -> r.getOrNull(i) ?: "" }))
            }
            clean.forEach { r -> appendLine(line(newHeader.map { r[it] ?: "" })) }
        })
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
