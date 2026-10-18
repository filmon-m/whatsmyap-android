package io.github.filmonm.whatsmyap.wlc

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Text helpers shared by the WLC parsers (ported from the prototype's wlc/parse.py).
 *
 * The parsers keep the prototype's shape on purpose: each returns a map of facts with the same keys,
 * so the unit tests can hold the Kotlin port to the Python answers on the same captured output.
 */
typealias Facts = MutableMap<String, Any?>

fun facts(vararg pairs: Pair<String, Any?>): Facts = linkedMapOf(*pairs)

/** One place for regex flags: "m" multiline, "s" dot-all, "i" ignore case. */
private val cache = HashMap<String, Regex>()
fun rx(pattern: String, flags: String = ""): Regex = synchronized(cache) {
    cache.getOrPut("$flags/$pattern") {
        Regex(pattern, buildSet {
            if ('m' in flags) add(RegexOption.MULTILINE)
            if ('s' in flags) add(RegexOption.DOT_MATCHES_ALL)
            if ('i' in flags) add(RegexOption.IGNORE_CASE)
        })
    }
}

/** Python re.search */
fun search(pattern: String, text: String, flags: String = ""): MatchResult? = rx(pattern, flags).find(text)

/** Python re.match: anchored at the start only. */
fun match(pattern: String, text: String, flags: String = ""): MatchResult? = rx("^(?:$pattern)", flags.replace("m", "")).find(text)

/** Python re.fullmatch */
fun fullmatch(pattern: String, text: String, flags: String = ""): Boolean = rx(pattern, flags).matches(text)

/** Group n of a search, or null. */
fun group(pattern: String, text: String, n: Int = 1, flags: String = ""): String? =
    search(pattern, text, flags)?.groups?.get(n)?.value

/** Python str.split() - whitespace, no empty pieces. */
fun String.words(): List<String> = trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

/** Python str.isdigit() */
fun String?.isDigits(): Boolean = !isNullOrEmpty() && all { it in '0'..'9' }

/** Python str.capitalize() - first letter upper, the rest lower. */
fun String.pyCapitalize(): String = if (isEmpty()) this else this[0].uppercase() + substring(1).lowercase()

/** Python f"{x:g}" for the values the WLCs report (no exponent form needed). */
fun fmtG(x: Number?): String {
    if (x == null) return "None"
    val d = x.toDouble()
    return BigDecimal(d).round(MathContext(6, RoundingMode.HALF_EVEN)).stripTrailingZeros().toPlainString()
}

/** Python round(x, n): half-even on the exact binary value. */
fun pyRound(x: Double, n: Int): Double = BigDecimal(x).setScale(n, RoundingMode.HALF_EVEN).toDouble()

/** Python f"{x:.0f}" */
fun fmt0(x: Number): String = BigDecimal(x.toDouble()).setScale(0, RoundingMode.HALF_EVEN).toPlainString()

/** Python str(x) for numbers in messages: 4 -> "4", 4.5 -> "4.5", 4.0 -> "4.0". */
fun pyStr(x: Any?): String = when (x) {
    null -> "None"
    is Double -> if (x == Math.floor(x) && !x.isInfinite()) "${x.toLong()}.0" else x.toString()
    is Float -> pyStr(x.toDouble())
    is Boolean -> if (x) "True" else "False"
    else -> x.toString()
}

/** Python truthiness for fact values. */
fun truthy(v: Any?): Boolean = when (v) {
    null -> false
    is Boolean -> v
    is Number -> v.toDouble() != 0.0
    is String -> v.isNotEmpty()
    is Collection<*> -> v.isNotEmpty()
    is Map<*, *> -> v.isNotEmpty()
    else -> true
}

fun Facts.str(key: String): String? = this[key] as String?
fun Facts.num(key: String): Number? = this[key] as Number?
fun Facts.int(key: String): Int? = (this[key] as Number?)?.toInt()
fun Facts.dbl(key: String): Double? = (this[key] as Number?)?.toDouble()

@Suppress("UNCHECKED_CAST")
fun Facts.list(key: String): List<Any?>? = this[key] as List<Any?>?

@Suppress("UNCHECKED_CAST")
fun Facts.maps(key: String): List<Facts>? = this[key] as List<Facts>?

/** Drop keys whose value is null or "" (the prototype's `if v not in (None, "")`). */
fun Facts.compact(): Facts = filterTo(linkedMapOf()) { (_, v) -> v != null && v != "" }

object Parse {

    /** Number after 'AP' in the AP name: 7761_AP14 -> 14, AP008 -> 8, AP-08 -> 8. */
    fun apNumber(name: String?): Int? = io.github.filmonm.whatsmyap.core.Labels.apNumber(name)

    /** 'Key : value' lines -> map (first occurrence wins, keys lower-cased). */
    fun parseKv(text: String): Map<String, String> {
        val kv = LinkedHashMap<String, String>()
        for (line in text.lines()) {
            val i = line.indexOf(':')
            if (i < 0) continue
            val k = line.substring(0, i).trim().lowercase()
            if (k.isNotEmpty() && k !in kv) kv[k] = line.substring(i + 1).trim()
        }
        return kv
    }

    /** Fixed-width tables (header row, then a '----  ----' row) -> rows per table. */
    fun dashTable(text: String): List<List<Map<String, String>>> {
        val lines = text.lines().let { if (text.endsWith("\n")) it.dropLast(1) else it }
        val tables = mutableListOf<List<Map<String, String>>>()
        for ((i, line) in lines.withIndex()) {
            if (i == 0 || !fullmatch("\\s*-+(\\s+-+)+\\s*", line)) continue
            val starts = rx("-+").findAll(line).map { it.range.first }.toList()
            val bounds = starts.mapIndexed { k, a -> a to starts.getOrNull(k + 1) }
            val header = lines[i - 1]
            val cols = bounds.map { (a, b) -> header.slice(a, b).trim() }
            val rows = mutableListOf<Map<String, String>>()
            for (row in lines.drop(i + 1)) {
                if (row.isBlank()) break
                val r = LinkedHashMap<String, String>()
                cols.zip(bounds).forEach { (c, ab) -> r[c] = row.slice(ab.first, ab.second).trim() }
                rows += r
            }
            tables += rows
        }
        return tables
    }

    /** Python s[a:b] with an open end and out-of-range tolerance. */
    private fun String.slice(a: Int, b: Int?): String {
        val start = minOf(a, length)
        val end = minOf(b ?: length, length)
        return if (end <= start) "" else substring(start, end)
    }

    fun speedToMbps(s: String?): Int? {
        val m = search("(\\d+(?:\\.\\d+)?)\\s*([GM])?", s ?: "", "i") ?: return null
        var n = m.groupValues[1].toDouble()
        if ((m.groups[2]?.value ?: "").uppercase() == "G" || n < 10) n *= 1000
        return n.toInt()
    }

    /** Compare-friendly MAC: hex digits only. */
    fun normMac(s: String?): String = (s ?: "").lowercase().replace(Regex("[^0-9a-f]"), "")

    /** 6c:d6:e3:3d:96:ce */
    fun colonMac(s: String?): String {
        val h = normMac(s)
        return if (h.length == 12) h.chunked(2).joinToString(":") else (s ?: "")
    }

    /** '802.11ax - 5 GHz' -> "5"; no band named -> "". */
    fun bandFromText(s: String?): String = group("(2\\.4|5|6)\\s*GHz", s ?: "", flags = "i") ?: ""
}
