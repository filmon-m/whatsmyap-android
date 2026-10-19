package io.github.filmonm.whatsmyap.data

import android.content.Context
import io.github.filmonm.whatsmyap.core.Rules

/**
 * The app's settings (the prototype's settings.ini), kept in SharedPreferences and edited
 * on the Settings screen. Every value has a default; a blank or invalid entry falls back to it.
 */
class Settings(context: Context) {

    companion object {
        // Microsoft's targets for the client -> Microsoft network edge path (Teams/Skype media guidance)
        const val TEAMS_RTT_MS = 100.0
        const val TEAMS_JITTER_MS = 30.0
        const val TEAMS_LOSS_PCT = 1.0
        const val TEAMS_RELAY = "worldaz.tr.teams.microsoft.com"
        const val TEAMS_PROBE_SECONDS = 10
        const val SPEED_24 = 25.0
        const val SPEED_5 = 100.0
        const val SPEED_6 = 100.0
        /** Internet throughput engines, tried in this order. */
        const val INTERNET_ORDER = "mlab,vultr,cloudflare"
        const val LOCAL_PORT = 8080              // ndt-server's usual cleartext ndt7 port

        val ENGINES = listOf("mlab", "vultr", "cloudflare")
    }

    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun num(key: String, def: Double) = p.getString(key, null)?.toDoubleOrNull() ?: def
    fun raw(key: String): String = p.getString(key, "") ?: ""
    fun set(key: String, value: String) = p.edit().putString(key, value.trim()).apply()

    val teamsRttMs get() = num("teams_rtt_ms", TEAMS_RTT_MS)
    val teamsJitterMs get() = num("teams_jitter_ms", TEAMS_JITTER_MS)
    val teamsLossPct get() = num("teams_loss_pct", TEAMS_LOSS_PCT)
    val teamsRelay get() = raw("teams_relay").ifBlank { TEAMS_RELAY }
    val speed24 get() = num("speed_24", SPEED_24)
    val speed5 get() = num("speed_5", SPEED_5)
    val speed6 get() = num("speed_6", SPEED_6)

    val internetOrder: List<String>
        get() = raw("internet_order").ifBlank { INTERNET_ORDER }.split(',', ' ')
            .map { it.trim().lowercase() }.filter { it in ENGINES }.distinct().ifEmpty { INTERNET_ORDER.split(',') }

    /** Show the AP from loaded site lists while surveying (off for vendor tablets). */
    var siteListLive: Boolean
        get() = p.getString("sitelist_live", "1") != "0"
        set(v) { p.edit().putString("sitelist_live", if (v) "1" else "0").apply() }

    /** Local test server ("192.0.2.10" or "192.0.2.10:8080"), e.g. the Pi in the site's MDF. Blank = none. */
    val localServer get() = raw("local_server")

    /** host:port for the local server, default port added. */
    fun localHostPort(): String? {
        val s = localServer.removePrefix("ws://").removePrefix("http://").trimEnd('/')
        if (s.isBlank()) return null
        return if (Regex(":\\d+$").containsMatchIn(s)) s else "$s:$LOCAL_PORT"
    }

    /** Push the limits into the rules every screen uses. */
    fun apply() {
        Rules.thresholds = mapOf("2.4" to speed24, "5" to speed5, "6" to speed6)
        Rules.teamsRttMs = teamsRttMs
        Rules.teamsJitterMs = teamsJitterMs
        Rules.teamsLossPct = teamsLossPct
    }
}
