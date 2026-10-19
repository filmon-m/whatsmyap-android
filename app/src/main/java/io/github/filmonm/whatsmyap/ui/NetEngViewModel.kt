package io.github.filmonm.whatsmyap.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.filmonm.whatsmyap.BuildConfig
import io.github.filmonm.whatsmyap.core.Labels
import io.github.filmonm.whatsmyap.data.Creds
import io.github.filmonm.whatsmyap.data.Storage
import io.github.filmonm.whatsmyap.data.WifiState
import io.github.filmonm.whatsmyap.data.WlcMemory
import io.github.filmonm.whatsmyap.wlc.Client
import io.github.filmonm.whatsmyap.wlc.Driver
import io.github.filmonm.whatsmyap.wlc.Facts
import io.github.filmonm.whatsmyap.wlc.Health
import io.github.filmonm.whatsmyap.wlc.HostKeyChanged
import io.github.filmonm.whatsmyap.wlc.Link
import io.github.filmonm.whatsmyap.wlc.LoginRejected
import io.github.filmonm.whatsmyap.wlc.Parse
import io.github.filmonm.whatsmyap.wlc.BssTable
import io.github.filmonm.whatsmyap.wlc.Row
import io.github.filmonm.whatsmyap.wlc.Verify
import io.github.filmonm.whatsmyap.wlc.VerifyItem
import io.github.filmonm.whatsmyap.wlc.WlcSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.net.Uri
import io.github.filmonm.whatsmyap.data.CsvLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.zip.ZipInputStream
import java.util.Date
import java.util.Locale
import java.util.UUID

data class Busy(val text: String, val step: Int = 0, val total: Int = 0)

data class InspectResult(
    val wifi: WifiState?, val client: Client?, val link: Link?, val error: String?, val time: String,
) {
    val apNumber get() = Parse.apNumber(client?.ap)
    /** Tablet's own band first (always reliable), WLC as fallback. */
    val band get() = wifi?.band?.ifEmpty { null } ?: client?.band ?: ""
    val bssidCheck: String?
        get() {
            val a = client?.bssid?.ifEmpty { null } ?: return null
            val b = wifi?.bssid?.ifEmpty { null } ?: return null
            return if (Parse.normMac(a) == Parse.normMac(b)) "MATCH" else "MISMATCH"
        }
}

data class ApResult(
    val ap: String, val mine: Boolean, val facts: Facts, val rows: List<Row>, val overall: String?, val link: Link?,
    val wifi: WifiState?, val client: Client?, val time: String,
)

data class WlcResult(val facts: Facts, val rows: List<Row>, val overall: String?, val time: String)

data class VerifyResult(val survey: String, val items: List<VerifyItem>, val wlcAps: Int, val time: String, val csv: File?)

data class NetEngState(
    val host: String = "",
    val recent: List<String> = emptyList(),
    val hasCreds: Boolean = false,
    val wlcName: String? = null,            // connected when set
    val wlcLabel: String = "",
    val vendor: String = "",
    val busy: Busy? = null,
    val error: String? = null,
    val keyChanged: HostKeyChanged? = null,
    val inspect: InspectResult? = null,
    val ap: ApResult? = null,
    val apNotFound: String? = null,
    val wlc: WlcResult? = null,
    val verify: VerifyResult? = null,
    val logged: String? = null,
)

/**
 * Network Engineer mode: one WLC session shared by Inspect, Debug my AP and Debug my WLC.
 * The login is unlocked with the PIN once and kept in memory until Lock (or the app closes).
 */
class NetEngViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx get() = getApplication<Application>()
    private val creds = Creds(app)
    val memory = WlcMemory(app)
    private val _state = MutableStateFlow(NetEngState(host = memory.recentHosts.firstOrNull() ?: "",
        recent = memory.recentHosts, hasCreds = creds.exists()))
    val state: StateFlow<NetEngState> = _state

    /** Field Survey's model: location, the label photo, the typed label number, speed tests, the survey file. */
    lateinit var field: FieldViewModel

    private var session: WlcSession? = null
    private var bss: Pair<Long, BssTable>? = null           // controller's BSSID table, cached a few minutes
    private var driver: Driver? = null
    private var login: Pair<String, String>? = null
    private val wlcLock = Mutex()

    val transcript: String get() = session?.transcript?.toString().orEmpty()

    fun setHost(h: String) = _state.update { it.copy(host = h.trim(), error = null) }
    fun dismiss() = _state.update { it.copy(error = null, logged = null) }

    private fun now() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    // ------------------------------------------------------------------ login
    /** First time: one test login, then the username/password are saved under the PIN. */
    fun firstLogin(user: String, pw: String, pin: String) {
        val host = _state.value.host
        work("Logging into $host (one attempt)") {
            connect(host, user, pw)
            withContext(Dispatchers.IO) { creds.save(user, pw, pin) }
            _state.update { it.copy(hasCreds = true) }
        }
    }

    fun unlock(pin: String) {
        val host = _state.value.host
        work("Checking PIN") {
            val c = withContext(Dispatchers.IO) { creds.load(pin) }
            if (c == null) {
                _state.update { it.copy(error = "Wrong PIN - the WLC was not contacted.") }
                return@work
            }
            busy("Logging into $host")
            connect(host, c.first, c.second)
        }
    }

    private suspend fun connect(host: String, user: String, pw: String) {
        if (host.isBlank()) throw IllegalArgumentException("Enter the WLC's IP address")
        closeSession()
        login = user to pw                       // kept so "trust the new key" can log straight back in
        val s = WlcSession(host, user, pw, memory)
        withContext(Dispatchers.IO) { s.connect() }
        session = s
        val d = Driver.forVendor(s.vendor!!, s)
        driver = d
        memory.remember(host)
        _state.update {
            it.copy(wlcName = d.name, wlcLabel = d.label, vendor = d.vendor, recent = memory.recentHosts,
                inspect = null, ap = null, wlc = null, apNotFound = null)
        }
    }

    /** After "the WLC's key changed": accept the new key and log in again with the unlocked login. */
    fun trustNewKey() {
        val k = _state.value.keyChanged ?: return
        memory.forgetKey(k.host)
        _state.update { it.copy(keyChanged = null) }
        val l = login
        if (l != null) work("Logging into ${k.host}") { connect(k.host, l.first, l.second) }
        else _state.update { it.copy(error = "Key cleared - log in again.") }
    }

    fun lock() {
        closeSession()
        login = null
        _state.update { it.copy(wlcName = null, inspect = null, ap = null, wlc = null, apNotFound = null) }
    }

    fun forgetLogin() {
        lock()
        creds.reset()
        _state.update { it.copy(hasCreds = false) }
    }

    private fun closeSession() {
        val s = session
        session = null
        driver = null
        bss = null
        if (s != null) viewModelScope.launch(Dispatchers.IO) { s.close() }
    }

    override fun onCleared() {
        session?.let { s -> Thread { s.close() }.start() }
    }

    // ------------------------------------------------------------------ running things
    private fun busy(text: String, step: Int = 0, total: Int = 0) = _state.update { it.copy(busy = Busy(text, step, total)) }

    /** One WLC job at a time, with errors shown rather than thrown. */
    private fun work(start: String, block: suspend () -> Unit) {
        if (_state.value.busy != null) return
        busy(start)
        _state.update { it.copy(error = null, logged = null) }
        viewModelScope.launch {
            try {
                wlcLock.withLock { block() }
            } catch (e: HostKeyChanged) {
                _state.update { it.copy(keyChanged = e) }
            } catch (e: LoginRejected) {
                closeSession()
                login = null
                _state.update {
                    it.copy(wlcName = null, error = e.message + if (it.hasCreds)
                        ". If your password changed, use 'Forget saved login' and set it up again." else "")
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            } finally {
                session?.onCommand = null
                _state.update { it.copy(busy = null) }
            }
        }
    }

    /** Count commands as they go out, for the progress line. */
    private fun counting(total: Int, before: Int = 0) {
        var n = before
        session?.onCommand = { cmd -> n++; busy(cmd, minOf(n, total), total) }
    }

    private fun requireDriver() = driver ?: throw IllegalStateException("Not connected to a WLC")

    private suspend fun readWifi(): WifiState = withContext(Dispatchers.Main) {
        field.refreshWifi()
        field.state.value.wifi ?: throw IllegalStateException(field.state.value.wifiError ?: "Not connected to Wi-Fi")
    }

    /** The AP the tablet is on, via the WLC (retry once: a tablet that just roamed may not be listed yet). */
    private suspend fun findMine(d: Driver): Pair<WifiState, Client?> {
        var wifi = readWifi()
        busy("Finding ${wifi.ip} on ${d.name}")
        var client = withContext(Dispatchers.IO) { d.findClient(wifi.ip) }
        if (client == null) {
            busy("Tablet not on the WLC yet - retrying in 5 s")
            delay(5000)
            wifi = readWifi()
            client = withContext(Dispatchers.IO) { d.findClient(wifi.ip) }
        }
        memory.learnSite(wifi.ip, Health.parseWlcName(d.name).first, Health.parseWlcName(d.name).second)
        return wifi to client
    }

    // ------------------------------------------------------------------ Inspect
    fun refreshInspect() = work("Reading tablet Wi-Fi") {
        val d = requireDriver()
        var wifi: WifiState? = null
        var client: Client? = null
        var link: Link? = null
        var error: String? = null
        try {
            val (w, c) = findMine(d)
            wifi = w; client = c
            if (c == null && w.bssid.isNotEmpty()) {          // IP not listed: which AP owns the BSSID we're on?
                busy("IP not listed - looking up the BSSID")
                bssTable(d).find(w.bssid)?.let { hit ->
                    client = Client(ap = hit.ap, bssid = w.bssid, band = hit.band, channel = "", rssiWlc = null, snrWlc = null,
                        ssid = hit.ssid, apIp = "", state = "via BSSID", raw = null)
                    error = "${w.ip} isn't in the WLC's client list - AP found from the BSSID instead"
                }
            }
            val c2 = client
            if (c2 == null) error = "${w.ip} not found on ${d.name}"
            else if (!c2.ap.isNullOrEmpty()) {
                busy("Checking AP uplink")
                link = runCatching { withContext(Dispatchers.IO) { d.apLink(c2.ap) } }.getOrNull()   // never let it break the result
            }
        } catch (e: IllegalStateException) {
            error = e.message
        }
        _state.update { it.copy(inspect = InspectResult(wifi, client, link, error, now())) }
    }

    fun labelResult(r: InspectResult?, label: Int?): String {
        val num = r?.apNumber
        return if (label == null || num == null) "UNKNOWN" else if (num == label) "MATCH" else "MISMATCH"
    }

    /** One Inspect row: what the WLC says, the typed label, the photo and any speed result in [extra]. */
    fun saveInspect(extra: Map<String, Any?> = emptyMap()) {
        val r = _state.value.inspect ?: return
        val d = driver
        val fs = field.state.value
        val w = r.wifi
        val c = r.client
        val label = fs.tag
        val (site, siteType) = Health.parseWlcName(_state.value.wlcName)
        val photo = field.photoHere(fs)
        val row = linkedMapOf<String, Any?>(
            "timestamp" to Storage.timestamp(), "module" to "inspect", "mode" to "neteng",
            "run_id" to UUID.randomUUID().toString().take(13), "app_version" to BuildConfig.VERSION_NAME,
            "site" to site, "site_type" to siteType, "network" to w?.ssid,
            "wlc_name" to _state.value.wlcName, "wlc_vendor" to (d?.vendor ?: _state.value.vendor),
            "ap_name" to c?.ap, "ap_number" to r.apNumber,
            "ssid" to w?.ssid, "band" to r.band, "channel" to (c?.channel?.ifEmpty { null } ?: w?.channel),
            "bssid" to w?.bssid, "tablet_ip" to w?.ip, "rssi_tablet_dbm" to w?.rssi,
            "tx_link_mbps" to w?.linkMbps, "gateway_ip" to w?.gateway,
            "label_entered" to label, "label_result" to labelResult(r, label), "bssid_check" to r.bssidCheck,
            "rssi_wlc_dbm" to c?.rssiWlc, "snr_wlc_db" to c?.snrWlc, "ap_ip" to c?.apIp?.ifEmpty { null },
            "link_status" to r.link?.status, "link_mbps" to r.link?.mbps, "link_duplex" to r.link?.duplex,
            "error" to r.error,
        )
        row.putAll(field.locationColumns())
        if (photo != null) row.putAll(photo.columns())
        row["label_verdict"] = Labels.verdict(r.apNumber, label, photo?.ocr?.number)?.name
        row.putAll(extra)
        field.write(listOf(row))
        _state.update { it.copy(logged = "Saved - ${c?.ap ?: "no AP"} at ${now()}") }
    }

    // ------------------------------------------------------------------ Debug my AP
    /** [typed] null = the AP the tablet is on; else an AP name, or a number on the current AP's pattern. */
    fun runApHealth(typed: String? = null) = work("Reading tablet Wi-Fi") {
        val d = requireDriver()
        var wifi: WifiState? = null
        var client: Client? = null
        val target: String
        val before: Int
        if (typed.isNullOrBlank()) {
            try {
                val (w, c) = findMine(d)
                wifi = w; client = c
            } catch (e: IllegalStateException) {
                _state.update { it.copy(apNotFound = e.message) }
                return@work
            }
            val ap = client?.ap
            if (ap.isNullOrEmpty()) {
                _state.update { it.copy(apNotFound = "${wifi?.ip ?: "This tablet"} not found on ${d.name}. Type an AP name or number.") }
                return@work
            }
            target = ap
            before = 2
        } else {
            target = Health.otherName(_state.value.ap?.ap, typed)
            before = 0
        }
        val total = before + d.apHealthCommands + 1
        counting(total, before)
        val f = withContext(Dispatchers.IO) { d.apHealth(target) }
        if (f["not_found"] == true) {
            _state.update { it.copy(apNotFound = "$target not found on ${d.name}. AP names are case-sensitive.") }
            return@work
        }
        val link = runCatching { withContext(Dispatchers.IO) { d.apLink(target) } }.getOrNull() ?: Link.of(f["eth"])
        busy("Parsing results", total, total)
        val mine = typed.isNullOrBlank()
        val (rows, overall) = Health.evaluateAp(f, link, if (mine) wifi?.band else null)
        val result = ApResult(target, mine, f, rows, overall, link, wifi, client, now())
        _state.update { it.copy(ap = result, apNotFound = null) }
        val runId = UUID.randomUUID().toString().take(13)
        val logRows = Health.apLogRows(d.name, d.vendor, f, rows, overall, link,
            network = if (mine) wifi?.ssid else null, tabletIp = if (mine) wifi?.ip else null)
        field.write(logRows.map { stamp(it, "debugmyap", runId) })
        _state.update { it.copy(logged = "Logged ${logRows.size} rows") }
    }

    // ------------------------------------------------------------------ Debug my WLC
    fun runWlcHealth(resetBaseline: Boolean = false) = work("Starting") {
        val d = requireDriver()
        val total = if (d.vendor == "aruba8") 10 else 9
        counting(total)
        val f = withContext(Dispatchers.IO) { d.wlcSummary() }
        busy("Parsing results", total, total)
        val (rows, overall) = Health.evaluateWlc(f, d.name, memory::apBaseline, resetBaseline)
        _state.update { it.copy(wlc = WlcResult(f, rows, overall, now())) }
        val runId = UUID.randomUUID().toString().take(13)
        val logRows = Health.wlcLogRows(d.name, d.vendor, f, rows, overall)
        field.write(logRows.map { stamp(it, "debugmywlc", runId) })
        _state.update { it.copy(logged = "Logged ${logRows.size} rows") }
    }

    // ------------------------------------------------------------------ Verify a survey
    private suspend fun bssTable(d: Driver): BssTable {
        bss?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000 }?.let { return it.second }
        busy("Reading the WLC's BSSID table (large on big controllers)")
        val t = withContext(Dispatchers.IO) { d.bssTable() }
        if (t.isEmpty) throw IllegalStateException("The WLC returned no BSSIDs - see WLC output (raw)")
        bss = System.currentTimeMillis() to t
        return t
    }

    /** Surveys saved on this tablet (newest first). */
    fun localSurveys(): List<String> = Storage.surveys(ctx).listFiles { f -> f.isDirectory && File(f, "${f.name}.csv").exists() }
        .orEmpty().sortedByDescending { File(it, "${it.name}.csv").lastModified() }.map { it.name }

    fun verifyLocal(name: String) = verify { name to CsvLog.read(File(File(Storage.surveys(ctx), name), "$name.csv")) }

    /** A survey zip from another tablet (part 1 holds the CSV). */
    fun verifyZip(uri: Uri) = verify {
        ctx.contentResolver.openInputStream(uri)!!.use { ins ->
            ZipInputStream(ins).use { z ->
                val e = generateSequence { z.nextEntry }.firstOrNull { e ->
                    !e.isDirectory && e.name.endsWith(".csv") && !e.name.substringAfterLast('/').startsWith("whatsmyap_")
                } ?: throw IllegalStateException("No survey CSV in that zip - pick part 1 of a survey zip")
                val rows = CsvLog.parse(z.readBytes().toString(Charsets.UTF_8))
                val header = rows.firstOrNull() ?: emptyList()
                e.name.substringAfterLast('/').removeSuffix(".csv") to
                    rows.drop(1).filter { it.size > 1 }.map { r -> header.mapIndexed { i, h -> h to (r.getOrNull(i) ?: "") }.toMap() }
            }
        }
    }

    /** [load] -> (survey name, its rows); runs off the main thread. */
    private fun verify(load: () -> Pair<String, List<Map<String, String>>>) = work("Reading the survey") {
        val d = requireDriver()
        val (name, rows) = withContext(Dispatchers.IO) { load() }
        if (rows.none { !it["bssid"].isNullOrBlank() }) throw IllegalStateException("$name has no BSSIDs to check")
        val table = bssTable(d)
        busy("Matching ${rows.size} rows")
        val items = Verify.run(rows, table)
        val runId = UUID.randomUUID().toString().take(13)
        val stamped = Verify.logRows(name, items, d.name, d.vendor).map { stamp(it, "verify", runId) }
        field.write(stamped, toSurvey = false)
        val csv = withContext(Dispatchers.IO) {
            File(Storage.share(ctx), "${name}_verified.csv").apply { delete(); CsvLog.write(this, stamped) }
        }
        _state.update { it.copy(verify = VerifyResult(name, items, table.aps, now(), csv), logged = "Logged ${items.size} rows") }
    }

    private fun stamp(row: Map<String, Any?>, module: String, runId: String): Map<String, Any?> = linkedMapOf<String, Any?>(
        "timestamp" to Storage.timestamp(), "module" to module, "mode" to "neteng",
        "run_id" to runId, "app_version" to BuildConfig.VERSION_NAME,
    ).apply { putAll(row); putAll(field.locationColumns()) }
}
