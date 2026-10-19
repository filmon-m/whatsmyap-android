package io.github.filmonm.whatsmyap.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.filmonm.whatsmyap.BuildConfig
import io.github.filmonm.whatsmyap.core.Labels
import io.github.filmonm.whatsmyap.core.Rules
import io.github.filmonm.whatsmyap.data.CsvLog
import io.github.filmonm.whatsmyap.data.LabelReader
import io.github.filmonm.whatsmyap.data.LocationSource
import io.github.filmonm.whatsmyap.data.Photo
import io.github.filmonm.whatsmyap.data.Place
import io.github.filmonm.whatsmyap.data.Results
import io.github.filmonm.whatsmyap.data.SpeedProgress
import io.github.filmonm.whatsmyap.data.SpeedResult
import io.github.filmonm.whatsmyap.data.Settings
import io.github.filmonm.whatsmyap.data.SpeedTest
import io.github.filmonm.whatsmyap.data.Storage
import io.github.filmonm.whatsmyap.data.Survey
import io.github.filmonm.whatsmyap.data.WifiSource
import io.github.filmonm.whatsmyap.data.WifiState
import io.github.filmonm.whatsmyap.data.WlcMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import java.util.UUID

/** One speed result, tied to the radio it was measured on. */
data class Speed(val result: SpeedResult, val bssid: String, val band: String, val roamed: Boolean) {
    val status get() = Rules.speedStatus(result.min, result.avg, band)
    private val probe get() = result.teams?.takeIf { it.ok }
    /** Teams verdict: from the UDP relay probe when it answered, else estimated from the TCP test. */
    val teams get() = probe?.let { Rules.teams(it.rttMs, it.jitterMs, it.lossPct) } ?: Rules.teams(result.latencyMs, result.jitterMs)
    val teamsSource get() = when {
        probe != null -> "udp-relay"
        result.latencyMs != null && result.jitterMs != null -> "tcp-estimate"
        else -> null
    }
}

data class FieldState(
    val wifi: WifiState? = null,
    val wifiError: String? = null,
    /** Strongest radio on our SSID, when it's another AP than the one we're on. */
    val closer: WifiSource.Seen? = null,
    val place: Place? = null,
    val locating: Boolean = false,
    val tag: Int? = null,
    val photo: Photo? = null,
    val readingLabel: Boolean = false,
    val speed: Speed? = null,
    val testing: SpeedProgress? = null,
    val survey: String? = null,
    val surveyStats: Survey.Stats? = null,
    val message: String? = null,
)

class FieldViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        /** A different AP this much louder than ours means we're not on the AP we're standing under. */
        const val CLOSER_DB = 10
    }

    private val ctx get() = getApplication<Application>()
    private val survey = Survey(app)
    private val speedTest = SpeedTest(app)
    private val _state = MutableStateFlow(FieldState(survey = survey.active))
    val state: StateFlow<FieldState> = _state
    private var testJob: Job? = null

    val settings = Settings(app)
    private val memory = WlcMemory(app)

    /**
     * While a Network Engineer screen (Inspect) is open, photo and speed results are logged there instead,
     * as part of that screen's row with the WLC's answer.
     */
    var redirect: ((Map<String, Any?>) -> Unit)? = null

    init {
        settings.apply()
        refreshSurvey()
    }

    /** After the Settings screen saves. */
    fun settingsChanged() {
        settings.apply()
        _state.update { it.copy(speed = it.speed?.copy()) }     // re-grade what's on screen
    }

    // ------------------------------------------------------------------ reads
    fun refreshWifi() {
        val w = runCatching { WifiSource.read(ctx) }.getOrNull()
        val near = w?.let { runCatching { WifiSource.nearest(ctx, it.ssid) }.getOrNull() }
        // a different AP that's clearly louder: the tablet is holding on to a farther one
        val closer = near?.takeIf { w != null && it.apKey != w.apKey && it.rssi - w.rssi >= CLOSER_DB }
        _state.update {
            it.copy(wifi = w, closer = closer, wifiError = when {
                w == null -> "Not connected to Wi-Fi"
                w.bssid.isEmpty() -> "Turn on Location and allow it for this app to see the AP (BSSID)"
                else -> null
            })
        }
    }

    fun refreshLocation(force: Boolean = false) {
        if (_state.value.locating || (!force && _state.value.place != null)) return
        _state.update { it.copy(locating = true) }
        viewModelScope.launch {
            val loc = runCatching { LocationSource.fix(ctx) }.getOrNull()
            val place = loc?.let { runCatching { LocationSource.describe(ctx, it) }.getOrNull() }
            _state.update { it.copy(place = place ?: it.place, locating = false) }
        }
    }

    fun setTag(text: String) {
        val n = Regex("\\d+").find(text)?.value?.toIntOrNull()
        _state.update { it.copy(tag = n) }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    /** Off, confirmed off, on again. false = this tablet doesn't allow it (the screen opens the Wi-Fi panel instead). */
    fun toggleWifi(onNotAllowed: () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(message = "Turning Wi-Fi off and on...") }
            if (!WifiSource.toggle(ctx)) {
                _state.update { it.copy(message = "Turn Wi-Fi off, then on, in the panel - the tablet will join the nearest AP") }
                onNotAllowed()
                return@launch
            }
            for (i in 0 until 30) {                               // up to 30 s to rejoin
                delay(1000)
                refreshWifi()
                if (_state.value.wifi?.bssid?.isNotEmpty() == true) break
            }
            _state.update { it.copy(message = _state.value.wifi?.let { w -> "Back on ${w.ssid} at ${w.rssi} dBm" } ?: "Wi-Fi is back on - waiting to join") }
        }
    }

    /** The photo/speed shown belong to the AP the tablet is on now. */
    fun photoHere(s: FieldState = _state.value): Photo? =
        s.photo?.takeIf { p -> s.wifi?.apKey?.let { it.isNotEmpty() && it == p.apKey } == true }

    fun speedHere(s: FieldState = _state.value): Speed? =
        s.speed?.takeIf { sp -> s.wifi?.bssid == sp.bssid }

    // ------------------------------------------------------------------ actions
    fun runSpeedTest(target: SpeedTest.Target = SpeedTest.Target.INTERNET) {
        val wifi = _state.value.wifi ?: return
        if (testJob?.isActive == true) return
        testJob = viewModelScope.launch {
            _state.update { it.copy(testing = SpeedProgress("Starting", 0f), message = null) }
            try {
                val r = speedTest.run(target) { p -> _state.update { it.copy(testing = p) } }
                refreshWifi()
                val after = _state.value.wifi?.bssid
                val sp = Speed(r, wifi.bssid, wifi.band, roamed = after != null && after != wifi.bssid)
                _state.update { it.copy(speed = sp, testing = null) }
                log(speedColumns(sp), wifiAt = wifi)
            } finally {
                _state.update { it.copy(testing = null) }
            }
        }
    }

    fun cancelSpeedTest() { testJob?.cancel() }

    /** Called by the camera screen with the saved original. Reads the label, fingerprints, logs. */
    fun onPhotoTaken(file: File) {
        val wifi = _state.value.wifi
        _state.update { it.copy(readingLabel = true) }
        viewModelScope.launch {
            val sha = withContext(Dispatchers.IO) { Storage.sha256(file) }
            val takenAt = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()   // before any rename
            val read = runCatching { LabelReader.read(ctx, file, _state.value.tag) }.getOrNull()
            val ocr = read?.first
            var f = file
            val n = _state.value.tag ?: ocr?.number
            if (n != null) {                                  // name the file after the AP
                val renamed = File(file.parentFile, file.nameWithoutExtension.substringBefore("_AP") + "_AP$n.jpg")
                if (!renamed.exists() && file.renameTo(renamed)) f = renamed
            }
            val photo = Photo(f, sha, Storage.timestamp(Date(takenAt)), wifi?.apKey.orEmpty(), ocr, ocrRan = true, ocrText = read?.second)
            _state.update { it.copy(photo = photo, readingLabel = false) }
            log(photo.columns(), wifiAt = wifi)
        }
    }

    // ------------------------------------------------------------------ survey
    fun refreshSurvey() {
        val name = survey.active
        _state.update { it.copy(survey = name, surveyStats = name?.let { n -> survey.stats(n) }) }
    }

    fun startSurvey(name: String) {
        runCatching { survey.start(name) }
            .onSuccess { resumed -> _state.update { it.copy(message = if (resumed) "Resumed survey ${survey.active}" else "Started survey ${survey.active}") } }
            .onFailure { e -> _state.update { it.copy(message = e.message) } }
        refreshSurvey()
    }

    fun endSurvey() { survey.stop(); refreshSurvey() }

    suspend fun packageSurvey(progress: (String) -> Unit): Survey.Packaged? {
        val name = survey.active ?: return null
        return withContext(Dispatchers.IO) { survey.packageZips(name, progress) }
    }

    suspend fun stageReport(): File? {
        val name = survey.active ?: return null
        return withContext(Dispatchers.IO) { survey.stage(name) }
    }

    /** Every check on this tablet (monthly logs), for the Results dashboard. */
    suspend fun stageResults(): File = withContext(Dispatchers.IO) { Results.stage(ctx) }

    // ------------------------------------------------------------------ logging
    fun speedColumns(sp: Speed): Map<String, Any?> {
        val r = sp.result
        val notes = listOfNotNull("ROAMED".takeIf { sp.roamed }, "ABORTED".takeIf { r.aborted })
        val teams = sp.teams
        return mapOf(
            "speed_min_mbps" to r.min, "speed_avg_mbps" to r.avg, "speed_max_mbps" to r.max,
            "speed_mb" to r.mb, "speed_secs" to r.secs, "speed_status" to sp.status?.name,
            "speed_note" to notes.joinToString(" "), "speed_error" to r.error,
            "speed_http_status" to r.httpStatus, "speed_site" to r.site, "speed_colo" to r.colo,
            "speed_up_mbps" to r.upMbps, "latency_ms" to r.latencyMs, "latency_loaded_ms" to r.loadedLatencyMs,
            "jitter_ms" to r.jitterMs,
            "speed_attempts" to r.attempts.joinToString(" \u2192 ").ifBlank { null },
            "speed_target" to r.target, "speed_retrans_pct" to r.retransPct,
            "teams_status" to teams?.let { if (it.pass) "PASS" else "FAIL" }, "teams_note" to teams?.why?.ifBlank { null },
            "teams_source" to sp.teamsSource,
            "teams_rtt_ms" to r.teams?.rttMs, "teams_jitter_ms" to r.teams?.jitterMs, "teams_loss_pct" to r.teams?.lossPct,
            "teams_sent" to r.teams?.sent?.takeIf { it > 0 }, "teams_relay" to r.teams?.relay, "teams_error" to r.teams?.error,
        )
    }

    private fun log(extra: Map<String, Any?>, wifiAt: WifiState?) {
        redirect?.let { it(extra); return }
        val s = _state.value
        val w = wifiAt ?: s.wifi
        val photo = s.photo?.takeIf { p -> w?.apKey?.let { it.isNotEmpty() && it == p.apKey } == true }
        val ocrNum = photo?.ocr?.number
        val row = linkedMapOf<String, Any?>(
            "timestamp" to Storage.timestamp(), "module" to "fieldsurvey", "mode" to "field",
            "run_id" to UUID.randomUUID().toString().take(13), "app_version" to BuildConfig.VERSION_NAME,
            "network" to w?.ssid, "ssid" to w?.ssid, "band" to w?.band, "channel" to w?.channel,
            "bssid" to w?.bssid, "tablet_ip" to w?.ip, "rssi_tablet_dbm" to w?.rssi,
            "tx_link_mbps" to w?.linkMbps, "gateway_ip" to w?.gateway,
            "ap_number" to (s.tag ?: ocrNum), "label_entered" to s.tag,
        )
        s.closer?.let { c -> row["closer_bssid"] = c.bssid; row["closer_rssi_dbm"] = c.rssi }
        memory.site(w?.ip)?.let { (site, type) -> row["site"] = site; row["site_type"] = type }   // learned on WLC visits
        row.putAll(locationColumns())
        if (photo != null) {
            row.putAll(photo.columns())
            row["label_verdict"] = Labels.verdict(null, s.tag, ocrNum)?.name
        }
        row.putAll(extra)
        write(listOf(row))
    }

    /** Where the tablet is, as log columns. */
    fun locationColumns(): Map<String, Any?> {
        val p = _state.value.place ?: return emptyMap()
        return linkedMapOf(
            "gps_lat" to "%.5f".format(java.util.Locale.US, p.lat),
            "gps_lon" to "%.5f".format(java.util.Locale.US, p.lon),
            "gps_accuracy_m" to p.accuracyM,
            "gps_source" to p.source,
            "location_city" to p.city.ifBlank { "N/A" },
            "location_name" to p.name.ifBlank { null },
            "location_address" to p.address.ifBlank { null },
        )
    }

    /** Rows to this month's log, and to the running survey's file when there is one (and [toSurvey]). */
    fun write(rows: List<Map<String, Any?>>, toSurvey: Boolean = true) {
        val name = survey.active?.takeIf { toSurvey }
        viewModelScope.launch(Dispatchers.IO) {
            CsvLog.write(File(Storage.logs(ctx), "whatsmyap_${Storage.month()}.csv"), rows)
            if (name != null) CsvLog.write(survey.csv(name), rows.map { it + ("survey" to name) })
            withContext(Dispatchers.Main) { refreshSurvey() }
        }
    }
}
