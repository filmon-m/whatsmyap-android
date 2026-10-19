package io.github.filmonm.whatsmyap.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import io.github.filmonm.whatsmyap.core.Rules
import java.net.Inet4Address

/** What the tablet sees of its Wi-Fi connection (replaces termux-wifi-connectioninfo). */
data class WifiState(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val band: String,
    val channel: Int?,
    val linkMbps: Int?,
    val ip: String,
    val gateway: String,
) {
    /** All of one AP's SSIDs/bands share the first 5 bytes of the radio MAC. */
    val apKey: String get() = if (bssid.length >= 14) bssid.lowercase().substring(0, 14) else ""
}

object WifiSource {

    /** Null when not on Wi-Fi. SSID/BSSID need the location permission and Location turned on. */
    @Suppress("DEPRECATION")
    fun read(context: Context): WifiState? {
        val app = context.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(net) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val wm = app.getSystemService(WifiManager::class.java)
        val info = wm.connectionInfo ?: return null
        val ssid = info.ssid.orEmpty().trim('"').let { if (it == "<unknown ssid>") "" else it }
        val bssid = info.bssid.orEmpty().let { if (it == "02:00:00:00:00:00") "" else it.lowercase() }
        val bc = Rules.bandChannel(info.frequency)
        val lp = cm.getLinkProperties(net)
        val ip = lp?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address }?.hostAddress.orEmpty()
        val gw = lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress.orEmpty()
        return WifiState(
            ssid = ssid, bssid = bssid, rssi = info.rssi, band = bc.band, channel = bc.channel,
            linkMbps = info.linkSpeed.takeIf { it > 0 }, ip = ip, gateway = gw,
        )
    }

    // ------------------------------------------------------------------ nearby APs
    private var lastScan = 0L

    /** Another radio heard nearby (from the system's Wi-Fi scan). */
    data class Seen(val bssid: String, val ssid: String, val rssi: Int, val freqMhz: Int) {
        val apKey get() = bssid.take(14)
    }

    /**
     * The strongest radio on the same SSID. Asks for a fresh scan at most every 30 s (Android allows a
     * foreground app 4 scans per 2 minutes); otherwise uses the system's latest results.
     */
    @Suppress("DEPRECATION")
    fun nearest(context: Context, ssid: String): Seen? {
        if (ssid.isEmpty()) return null
        val wm = context.applicationContext.getSystemService(WifiManager::class.java)
        val now = System.currentTimeMillis()
        if (now - lastScan > 30_000) { lastScan = now; runCatching { wm.startScan() } }
        return runCatching { wm.scanResults }.getOrNull().orEmpty()
            .filter { it.SSID == ssid && !it.BSSID.isNullOrEmpty() }
            .maxByOrNull { it.level }
            ?.let { Seen(it.BSSID.lowercase(), it.SSID, it.level, it.frequency) }
    }

    /**
     * Turn Wi-Fi off, wait for the OS to confirm, turn it back on - the tablet then joins the strongest AP.
     * Android 10+ only lets system / device-owner apps do this; elsewhere returns false and the caller opens
     * the Wi-Fi panel so the tester flips it.
     */
    @Suppress("DEPRECATION")
    suspend fun toggle(context: Context): Boolean {
        val wm = context.applicationContext.getSystemService(WifiManager::class.java)
        if (!runCatching { wm.setWifiEnabled(false) }.getOrDefault(false)) return false
        for (i in 0 until 50) {                                   // up to 10 s for "off"
            if (wm.wifiState == WifiManager.WIFI_STATE_DISABLED) break
            kotlinx.coroutines.delay(200)
        }
        return runCatching { wm.setWifiEnabled(true) }.getOrDefault(false)
    }
}
