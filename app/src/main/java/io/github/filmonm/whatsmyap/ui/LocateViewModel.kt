package io.github.filmonm.whatsmyap.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.filmonm.whatsmyap.BuildConfig
import io.github.filmonm.whatsmyap.core.Labels
import io.github.filmonm.whatsmyap.data.CsvLog
import io.github.filmonm.whatsmyap.data.Storage
import io.github.filmonm.whatsmyap.data.Survey
import io.github.filmonm.whatsmyap.wlc.Health
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import java.util.UUID

/** One AP in a locate survey, as collected so far. */
data class LocatedAp(val label: String, val photos: Int, val note: String?, val last: String)

data class LocateState(
    val survey: String? = null,
    val current: String? = null,          // the AP label photos and notes go to now
    val aps: List<LocatedAp> = emptyList(),
    val message: String? = null,
)

/**
 * Survey (locate APs): walk a site and record where each AP is - its label, photos (wide shot, close-up),
 * and a note on anything that doesn't match the plans ("in Room 12, not Room 11").
 * No label check and no requirement to be on that AP: this is a record of where things are.
 * Rows go to the monthly log and to the locate survey's own file; the survey zips like a Field Survey.
 */
class LocateViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx get() = getApplication<Application>()
    val survey = Survey(app, prefsName = "survey_locate")
    private val _state = MutableStateFlow(LocateState(survey = survey.active))
    val state: StateFlow<LocateState> = _state

    lateinit var field: FieldViewModel
    lateinit var neteng: NetEngViewModel

    init { reload() }

    /** The APs in the running survey, newest first, from its file. */
    fun reload() {
        val name = survey.active
        viewModelScope.launch {
            val aps = if (name == null) emptyList() else withContext(Dispatchers.IO) {
                CsvLog.read(survey.csv(name)).filter { !it["ap_label"].isNullOrBlank() }
                    .groupBy { it.getValue("ap_label") }
                    .map { (label, rows) ->
                        LocatedAp(label, rows.mapNotNull { it["photo_file"]?.ifBlank { null } }.toSet().size,
                            rows.lastOrNull { !it["comment"].isNullOrBlank() }?.get("comment"), rows.last()["timestamp"].orEmpty())
                    }.sortedByDescending { it.last }
            }
            _state.update { it.copy(survey = name, aps = aps) }
        }
    }

    fun start(name: String) {
        runCatching { survey.start(name) }
            .onSuccess { resumed -> _state.update { it.copy(message = if (resumed) "Resumed ${survey.active}" else "Started ${survey.active}") } }
            .onFailure { e -> _state.update { it.copy(message = e.message) } }
        reload()
    }

    fun end() { survey.stop(); _state.update { it.copy(current = null) }; reload() }

    fun dismiss() = _state.update { it.copy(message = null) }

    /** Entering a label starts collection for that AP; later photos and notes belong to it. */
    fun setAp(label: String) {
        val l = label.trim()
        if (l.isEmpty()) return
        _state.update { it.copy(current = l, message = null) }
    }

    /** A photo from the camera: renamed after the AP, fingerprinted, logged. */
    fun onPhoto(file: File) {
        val label = _state.value.current ?: return
        viewModelScope.launch {
            val row = withContext(Dispatchers.IO) {
                val taken = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
                val base = Storage.fileStamp(Date(taken)) + "_" + Survey.cleanName(label).ifEmpty { "AP" }
                var f = File(file.parentFile, "$base.jpg")
                var n = 2
                while (f.exists()) f = File(file.parentFile, "${base}_${n++}.jpg")
                val final = if (file.renameTo(f)) f else file
                linkedMapOf<String, Any?>(
                    "photo_file" to final.name, "photo_sha256" to Storage.sha256(final),
                    "photo_taken" to Storage.timestamp(Date(taken)),
                )
            }
            write(label, row)
        }
    }

    fun saveNote(text: String) {
        val label = _state.value.current ?: return
        if (text.isBlank()) return
        write(label, mapOf("comment" to text.trim()))
        _state.update { it.copy(message = "Note saved for $label") }
    }

    private fun write(label: String, extra: Map<String, Any?>) {
        val name = survey.active ?: return
        val fs = field.state.value
        val w = fs.wifi
        val ne = neteng.state.value
        val (site, siteType) = ne.wlcName?.let { Health.parseWlcName(it) }
            ?: neteng.memory.site(w?.ip) ?: ("" to "")
        // what the WLC said the tablet was on, if Inspect looked at this same radio - context only, not a check
        val seen = ne.inspect?.takeIf { it.wifi?.bssid == w?.bssid }?.client?.ap
        val row = linkedMapOf<String, Any?>(
            "timestamp" to Storage.timestamp(), "module" to "locate", "mode" to "locate",
            "run_id" to UUID.randomUUID().toString().take(13), "app_version" to BuildConfig.VERSION_NAME,
            "site" to site.ifEmpty { null }, "site_type" to siteType.ifEmpty { null },
            "wlc_name" to ne.wlcName, "wlc_vendor" to ne.vendor.ifEmpty { null },
            // the label as entered names the AP here (there is no WLC check in a locate survey)
            "ap_name" to label, "ap_number" to Labels.apNumber(label), "ap_label" to label,
            "network" to w?.ssid, "ssid" to w?.ssid, "band" to w?.band,
            "tablet_bssid" to w?.bssid, "tablet_ip" to w?.ip, "rssi_tablet_dbm" to w?.rssi,
            "tablet_on_ap" to seen,
        )
        row.putAll(field.locationColumns())
        row.putAll(extra)
        viewModelScope.launch(Dispatchers.IO) {
            CsvLog.write(File(Storage.logs(ctx), "whatsmyap_${Storage.month()}.csv"), listOf(row))
            CsvLog.write(survey.csv(name), listOf(row + ("survey" to name)))
            withContext(Dispatchers.Main) { reload() }
        }
    }

    suspend fun packageSurvey(progress: (String) -> Unit): Survey.Packaged? {
        val name = survey.active ?: return null
        return withContext(Dispatchers.IO) { survey.packageZips(name, progress) }
    }

    suspend fun stageReport(): File? {
        val name = survey.active ?: return null
        return withContext(Dispatchers.IO) { survey.stage(name) }
    }
}
