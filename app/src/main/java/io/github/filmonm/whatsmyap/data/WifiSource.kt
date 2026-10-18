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
}
