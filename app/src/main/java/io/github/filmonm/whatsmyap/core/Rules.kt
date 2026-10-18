package io.github.filmonm.whatsmyap.core

import kotlin.math.abs

/** Wi-Fi band/channel from frequency, speed and Teams thresholds (ported from the prototype). */
object Rules {

    data class BandChannel(val band: String, val channel: Int?)

    /** 2412 -> 2.4/1, 5540 -> 5/108, 5955 -> 6/1. Band is "2.4" | "5" | "6" | "". */
    fun bandChannel(freqMhz: Int?): BandChannel {
        val f = freqMhz ?: return BandChannel("", null)
        return when (f) {
            2484 -> BandChannel("2.4", 14)
            in 2400..2500 -> BandChannel("2.4", (f - 2407) / 5)
            in 4900..5899 -> BandChannel("5", (f - 5000) / 5)
            in 5925..7125 -> BandChannel("6", (f - 5950) / 5)
            else -> BandChannel("", null)
        }
    }

    fun bandLabel(band: String) = if (band.isEmpty()) "?" else "$band GHz"

    /** Average download (Mbps) needed to pass, per band. 6 GHz uses the 5 GHz bar. */
    var thresholds = mapOf("2.4" to 25.0, "5" to 100.0, "6" to 100.0)

    enum class Status { PASS, WARN, FAIL }

    /** PASS, WARN (average passes but the minimum dips under), FAIL, or null (no result / band unknown). */
    fun speedStatus(min: Double?, avg: Double?, band: String): Status? {
        val th = thresholds[band] ?: return null
        if (avg == null) return null
        return when {
            avg < th -> Status.FAIL
            (min ?: avg) < th -> Status.WARN
            else -> Status.PASS
        }
    }

    // Microsoft's client -> Microsoft edge targets; Settings can change them
    var teamsRttMs = 100.0
    var teamsJitterMs = 30.0
    var teamsLossPct = 1.0

    data class Teams(val pass: Boolean, val why: String)

    /** Microsoft Teams check: round trip, jitter and (when measured) packet loss within limits.
     *  Null without latency figures. */
    fun teams(rttMs: Double?, jitterMs: Double?, lossPct: Double? = null): Teams? {
        if (rttMs == null || jitterMs == null) return null
        val bad = buildList {
            if (rttMs > teamsRttMs) add("RTT ${rttMs.toInt()} > ${teamsRttMs.toInt()} ms")
            if (jitterMs > teamsJitterMs) add("jitter ${jitterMs.toInt()} > ${teamsJitterMs.toInt()} ms")
            if (lossPct != null && lossPct > teamsLossPct) add("loss %.1f%% > %s%%".format(java.util.Locale.US, lossPct, fmt(teamsLossPct)))
        }
        return Teams(bad.isEmpty(), bad.joinToString(", "))
    }

    private fun fmt(d: Double) = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    /** Median and jitter (mean change between consecutive samples) of round-trip times. */
    fun latencyStats(samples: List<Double>): Pair<Double, Double>? {
        if (samples.isEmpty()) return null
        val s = samples.sorted()
        val median = if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        val jitter = if (samples.size < 2) 0.0 else samples.zipWithNext { a, b -> abs(a - b) }.average()
        return median to jitter
    }

    /** Short name for a test server URL: lax-ca-us-ping.vultr.com -> vultr-lax. */
    fun siteName(url: String): String {
        val host = Regex("^[a-z]+://([^/:]+)").find(url)?.groupValues?.get(1) ?: return url
        if (Regex("[\\d.]+").matches(host)) return host
        val parts = host.split(".")
        return when {
            parts.size > 2 -> "${parts[parts.size - 2]}-${parts[0].substringBefore('-')}"
            parts.size == 2 -> parts[0]
            else -> host
        }
    }
}
