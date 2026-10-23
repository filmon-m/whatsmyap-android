package io.github.filmonm.whatsmyap.ui

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.filmonm.whatsmyap.core.Rules
import io.github.filmonm.whatsmyap.data.SpeedTest
import io.github.filmonm.whatsmyap.wlc.Health
import io.github.filmonm.whatsmyap.wlc.Verify
import io.github.filmonm.whatsmyap.wlc.Row as HealthRow

private fun colorOf(status: String?) = when (status) {
    "PASS", "MATCH", "VERIFIED", "CONFIRMED" -> Good
    "WARN", "UNKNOWN" -> Warn
    "FAIL", "MISMATCH" -> Bad
    else -> Color.Unspecified
}

@Composable
private fun Screen(title: String, onBack: () -> Unit, back: String = "‹ Back", content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text(back) }
                    Text(title, style = MaterialTheme.typography.titleLarge)
                }
                content()
            },
        )
    }
}

/** Progress line, error, "logged" note, and the WLC transcript - the same on every Network Engineer screen. */
@Composable
private fun Status(vm: NetEngViewModel, s: NetEngState) {
    s.busy?.let { b ->
        Column {
            Text(if (b.total > 0) "${b.step}/${b.total}  ${b.text}" else b.text, style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace, maxLines = 1)
            if (b.total > 0) LinearProgressIndicator(progress = { b.step.toFloat() / b.total }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    s.error?.let { Text(it, color = Bad) }
    s.logged?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    s.keyChanged?.let { k ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("WLC key changed") },
            text = { Text("${k.host} presented a different SSH key than on your last visit.\n\n${k.message}\n\nNew key: ${k.fingerprint}") },
            confirmButton = { TextButton(onClick = vm::trustNewKey) { Text("Trust new key") } },
            dismissButton = { TextButton(onClick = vm::lock) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TranscriptButton(vm: NetEngViewModel) {
    var show by remember { mutableStateOf(false) }
    val context = LocalContext.current
    TextButton(onClick = { show = true }) { Text("WLC output (raw)") }
    if (show) {
        val text = vm.transcript.ifEmpty { "Nothing yet." }
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text("What the WLC sent") },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                    Text(text.takeLast(60_000), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text); putExtra(Intent.EXTRA_SUBJECT, "WLC output")
                    }, "Share WLC output"))
                }) { Text("Share") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun HealthRows(rows: List<HealthRow>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (r in rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Health.ICON[r.status] ?: " ", color = colorOf(r.status), fontWeight = FontWeight.Bold, modifier = Modifier.width(14.dp))
                    Text(r.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(76.dp))
                    Text(r.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun Overall(overall: String?, rows: List<HealthRow>) {
    val issues = rows.count { it.status == "WARN" || it.status == "FAIL" }
    Text("Status: ${overall ?: "?"}" + if (issues > 0) "  (${count(issues, "item")} to check)" else "",
        color = colorOf(overall), style = MaterialTheme.typography.titleMedium)
}

// ==================================================================================== hub + login
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetEngScreen(vm: NetEngViewModel, onLocate: () -> Unit, onInspect: () -> Unit, onDebugAp: () -> Unit, onDebugWlc: () -> Unit, onVerify: () -> Unit, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    Screen("Network Engineer", onBack, back = "‹ Home") {
        if (s.wlcName == null) {
            Login(vm, s)
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(s.wlcName!!, style = MaterialTheme.typography.titleMedium)
                    Text("${s.wlcLabel} · ${s.host}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Tile("Survey (locate APs)", "Record where each AP or IDF is: label, photos, a note. No checks; zipped like a Field Survey.", onLocate)
            Tile("Inspect", "Which AP am I on? Compare it with the label, photo and speed.", onInspect)
            Tile("Debug my AP", "Health of the AP you're under (or any AP by name or number).", onDebugAp)
            Tile("Debug my WLC", "Controller health: CPU, memory, uplinks, APs down, clients.", onDebugWlc)
            Tile("Verify a survey", "Check a field survey's labels against this WLC - from this tablet or a survey zip.", onVerify)
            Tile("Save site AP list", "This WLC's BSSID-to-AP list, for testers without a WLC login. Saved here; share it to their tablets.",
                { vm.saveSiteList() })
            s.siteList?.let { f ->
                val context = LocalContext.current
                OutlinedButton(onClick = {
                    val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files",
                        java.io.File(java.io.File(context.cacheDir, "share").apply { mkdirs() }, f.name).also { f.copyTo(it, overwrite = true) })
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"; putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_SUBJECT, "Site AP list ${f.nameWithoutExtension}")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Share site list"))
                }) { Text("Share site list ${f.nameWithoutExtension}") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::lock) { Text("Lock") }
                TextButton(onClick = vm::forgetLogin) { Text("Forget saved login") }
            }
        }
        Status(vm, s)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Tile(title: String, text: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Login(vm: NetEngViewModel, s: NetEngState) {
    var user by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    val idle = s.busy == null
    val numeric = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
    val hidden = PasswordVisualTransformation()

    OutlinedTextField(s.host, vm::setHost, label = { Text("WLC IP address") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
    if (s.recent.size > 1 || (s.recent.isNotEmpty() && s.recent[0] != s.host)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            s.recent.forEach { h -> AssistChip(onClick = { vm.setHost(h) }, label = { Text(h) }) }
        }
    }
    if (s.hasCreds) {
        OutlinedTextField(pin, { pin = it.filter(Char::isDigit) }, label = { Text("PIN") }, singleLine = true,
            visualTransformation = hidden, keyboardOptions = numeric, modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.unlock(pin); pin = "" }, enabled = idle && pin.length >= 4 && s.host.isNotBlank()) { Text("Connect") }
        TextButton(onClick = vm::forgetLogin) { Text("Forget saved login") }
    } else {
        Text("First-time setup: your WLC login is tested once, then saved on this tablet, locked with a PIN. " +
            "After that you only need the PIN.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(user, { user = it.trim() }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, singleLine = true, visualTransformation = hidden,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pw2, { pw2 = it }, label = { Text("Password again") }, singleLine = true, visualTransformation = hidden,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
            isError = pw2.isNotEmpty() && pw2 != pw)
        OutlinedTextField(pin, { pin = it.filter(Char::isDigit) }, label = { Text("Choose a PIN (4+ digits)") }, singleLine = true,
            visualTransformation = hidden, keyboardOptions = numeric, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pin2, { pin2 = it.filter(Char::isDigit) }, label = { Text("PIN again") }, singleLine = true,
            visualTransformation = hidden, keyboardOptions = numeric, modifier = Modifier.fillMaxWidth(),
            isError = pin2.isNotEmpty() && pin2 != pin)
        val ready = user.isNotEmpty() && pw.isNotEmpty() && pw == pw2 && pin.length >= 4 && pin == pin2 && s.host.isNotBlank()
        Button(onClick = { vm.firstLogin(user, pw, pin) }, enabled = idle && ready) { Text("Test login & save") }
        Text("One login attempt only - a rejected login is never retried (account lockout guard).",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ==================================================================================== Inspect
@Composable
fun InspectScreen(vm: NetEngViewModel, field: FieldViewModel, onCamera: () -> Unit, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val fs by field.state.collectAsStateWithLifecycle()
    var askTarget by remember { mutableStateOf(false) }
    // photos and speed tests taken here are logged as part of this AP's Inspect row
    DisposableEffect(Unit) {
        field.redirect = { extra -> vm.saveInspect(extra) }
        onDispose { field.redirect = null }
    }
    LaunchedEffect(Unit) {
        field.refreshLocation()
        if (s.inspect == null) vm.refreshInspect()
    }
    Screen("What's My AP?", onBack) {
        val r = s.inspect
        val c = r?.client
        val w = r?.wifi
        Text("WLC:   ${s.wlcName} (${s.wlcLabel})")
        Text("Where: " + (fs.place?.text() ?: if (fs.locating) "locating..." else "N/A"), style = MaterialTheme.typography.bodySmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    c == null -> Text("AP:    --")
                    c.ap.isNullOrEmpty() -> Text("AP:    ? (see WLC output)", color = Warn)
                    else -> Text("AP:    ${c.ap}" + (r?.apNumber?.let { "  (#$it)" } ?: ""), fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium)
                }
                val label = fs.tag
                val lr = vm.labelResult(r, label)
                when {
                    label == null -> Text("Label: type the label # below", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    lr == "UNKNOWN" -> Text("Label: $label   can't compare", color = Warn)
                    else -> Text("Label: $label   $lr", color = colorOf(lr), fontWeight = FontWeight.SemiBold)
                }
                when (r?.bssidCheck) {
                    "MATCH" -> Text("BSSID: tablet = WLC", color = Good)
                    "MISMATCH" -> Text("BSSID: differs - roaming? refresh", color = Bad)
                }
                if (c != null) {
                    val l = r?.link
                    when {
                        l == null -> Text("Link:  ? (not available)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        l.status != "UP" -> Text("Link:  DOWN (${l.iface})", color = Bad)
                        l.mbps >= 1000 && l.duplex.lowercase().startsWith("full") -> Text("Link:  ${Health.fmtMbps(l.mbps)} ${l.duplex}", color = Good)
                        else -> Text("Link:  ${Health.fmtMbps(l.mbps)} ${l.duplex}  <- check cable/switch", color = Warn)
                    }
                }
                if (w != null) {
                    Text("Band:  ${Rules.bandLabel(r?.band ?: "")}    Channel: ${c?.channel?.ifEmpty { null } ?: w.channel ?: "?"}")
                    val wlcRssi = c?.rssiWlc?.let { " / WLC $it" } ?: ""
                    val snr = c?.snrWlc?.let { "   SNR $it dB" } ?: if (c != null && s.vendor == "aruba8") "   SNR pending" else ""
                    Text("RSSI:  tablet ${w.rssi}$wlcRssi dBm$snr")
                    fs.closer?.let { cl -> Text("A closer AP is louder (${cl.rssi} dBm, ${cl.bssid}) - toggle Wi-Fi to move to it", color = Bad) }
                    Text("SSID:  ${w.ssid.ifBlank { "? (turn Location on)" }}")
                    Text("IP:    ${w.ip}")
                }
                r?.error?.let { Text(it, color = Bad) }
                c?.raw?.let { raw -> Text(raw.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                r?.let { Text("Updated ${it.time}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        OutlinedTextField(fs.tag?.toString() ?: "", field::setTag, label = { Text("AP number on the label") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::refreshInspect, enabled = s.busy == null) { Text("Refresh") }
            Button(onClick = { vm.saveInspect() }, enabled = s.busy == null && r != null) { Text("Save check") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCamera, enabled = c?.ap != null) { Text("Label photo") }
            if (fs.testing == null) OutlinedButton(onClick = {
                if (field.settings.localHostPort() != null) askTarget = true else field.runSpeedTest()
            }, enabled = w != null) { Text("Speed test") }
            else OutlinedButton(onClick = field::cancelSpeedTest) { Text("Stop test") }
        }
        fs.testing?.let { t ->
            Column {
                Text(t.phase + (t.mbps?.let { "  %.0f Mbps".format(it) } ?: ""), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { t.fraction }, modifier = Modifier.fillMaxWidth())
            }
        }
        PhotoCard(field, fs, wlcNumber = r?.apNumber)
        SpeedCard(field, fs)
        Status(vm, s)
        TranscriptButton(vm)
    }
    if (askTarget) {
        AlertDialog(
            onDismissRequest = { askTarget = false },
            title = { Text("Speed test to...") },
            text = { Text("Local: the site's test server (${field.settings.localHostPort()}).\nInternet: Teams check + M-Lab / Vultr.") },
            confirmButton = { TextButton(onClick = { askTarget = false; field.runSpeedTest(SpeedTest.Target.LOCAL) }) { Text("Local server") } },
            dismissButton = { TextButton(onClick = { askTarget = false; field.runSpeedTest(SpeedTest.Target.INTERNET) }) { Text("Internet") } },
        )
    }
}

// ==================================================================================== Debug my AP
@Composable
fun DebugApScreen(vm: NetEngViewModel, field: FieldViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    var typed by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        field.refreshLocation()
        if (s.ap == null) vm.runApHealth(null)
    }
    Screen("AP Health", onBack) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(typed, { typed = it.trim() }, label = { Text("Other AP: name or number") }, singleLine = true,
                modifier = Modifier.weight(1f))
            Button(onClick = { vm.runApHealth(typed) }, enabled = s.busy == null && typed.isNotEmpty()) { Text("Check") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { typed = ""; vm.runApHealth(null) }, enabled = s.busy == null) { Text("My AP") }
            s.ap?.let { a -> OutlinedButton(onClick = { vm.runApHealth(if (a.mine) null else a.ap) }, enabled = s.busy == null) { Text("Re-run") } }
        }
        Status(vm, s)
        s.apNotFound?.let { Text(it, color = Bad) }
        s.ap?.let { a ->
            val f = a.facts
            val num = io.github.filmonm.whatsmyap.wlc.Parse.apNumber(a.ap)
            Text(a.ap + (num?.let { "  (#$it)" } ?: "") + if (a.mine) "" else "  (typed)",
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            listOfNotNull(f["model"], f["ip"], f["location"]).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("WLC: ${s.wlcName}", style = MaterialTheme.typography.bodySmall)
            if (a.mine && a.wifi != null) {
                val bits = listOfNotNull("${Rules.bandLabel(a.wifi.band)} ch ${a.client?.channel?.ifEmpty { null } ?: a.wifi.channel}",
                    "tablet ${a.wifi.rssi} dBm", a.client?.snrWlc?.let { "SNR $it dB" })
                Text("You: " + bits.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
            Overall(a.overall, a.rows)
            HealthRows(a.rows)
            @Suppress("UNCHECKED_CAST") val missing = f["missing"] as List<String>?
            if (!missing.isNullOrEmpty()) Text("Couldn't read: ${missing.joinToString(", ")}", color = Warn)
            Text("Checked ${a.time}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TranscriptButton(vm)
    }
}

// ==================================================================================== Debug my WLC
@Composable
fun DebugWlcScreen(vm: NetEngViewModel, field: FieldViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    var allDown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        field.refreshLocation()
        if (s.wlc == null) vm.runWlcHealth()
    }
    Screen("WLC Health", onBack) {
        Text(s.wlcName ?: "", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.runWlcHealth() }, enabled = s.busy == null) { Text("Re-run") }
        }
        Status(vm, s)
        s.wlc?.let { w ->
            val f = w.facts
            Text(listOfNotNull(f["model"] ?: s.wlcLabel, f["version"]).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            Overall(w.overall, w.rows)
            HealthRows(w.rows)
            @Suppress("UNCHECKED_CAST") val down = f["_down"] as List<String>?
            if (!down.isNullOrEmpty()) {
                Text(if (f["_max_seen"] != null) "Not registered:" else "Down APs:", fontWeight = FontWeight.SemiBold)
                val shown = if (allDown) down else down.take(6)
                Text(shown.joinToString(", ") + if (shown.size < down.size) " +${down.size - shown.size} more" else "")
                if (down.size > 6) TextButton(onClick = { allDown = !allDown }) { Text(if (allDown) "Show fewer" else "Show all") }
            }
            @Suppress("UNCHECKED_CAST") val ssids = f["ssids"] as List<Map<String, Any?>>?
            if (!ssids.isNullOrEmpty()) {
                Text("Clients by SSID:", fontWeight = FontWeight.SemiBold)
                Text(ssids.sortedBy { -((it["clients"] as Number?)?.toInt() ?: 0) }.joinToString(" · ") { x ->
                    @Suppress("UNCHECKED_CAST") val b = (x["bands"] as Map<String, Int>?) ?: emptyMap()
                    val split = listOf("5", "6", "2.4").filter { (b[it] ?: 0) != 0 }.joinToString(" / ") { "$it GHz ${b[it]}" }
                    "${x["ssid"]} ${x["clients"]}" + if (split.isNotEmpty() && b.size > 1) " ($split)" else ""
                })
            }
            @Suppress("UNCHECKED_CAST") val busiest = f["busiest_aps"] as List<List<Any?>>?
            if (!busiest.isNullOrEmpty()) {
                Text("Busiest APs:", fontWeight = FontWeight.SemiBold)
                Text(busiest.joinToString(" · ") { "${it[0]} ${it[1]}" })
            }
            if (f["_max_seen"] != null) {
                TextButton(onClick = { vm.runWlcHealth(resetBaseline = true) }, enabled = s.busy == null) {
                    Text("Accept today's AP count as normal")
                }
            }
            @Suppress("UNCHECKED_CAST") val missing = f["missing"] as List<String>?
            if (!missing.isNullOrEmpty()) Text("Couldn't read: ${missing.joinToString(", ")}", color = Warn)
            Text("Checked ${w.time}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TranscriptButton(vm)
    }
}

// ==================================================================================== Verify a survey
@Composable
fun VerifyScreen(vm: NetEngViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val surveys = remember { vm.localSurveys() }
    val pickZip = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::verifyZip) }
    Screen("Verify a survey", onBack) {
        Text("Every BSSID the survey recorded is looked up on ${s.wlcName}, and the AP number the WLC gives is compared " +
            "with the label (the photo's OCR first, then the typed number).", style = MaterialTheme.typography.bodySmall)
        if (surveys.isNotEmpty()) {
            Text("On this tablet:", fontWeight = FontWeight.SemiBold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                surveys.forEach { n -> AssistChip(onClick = { vm.verifyLocal(n) }, enabled = s.busy == null, label = { Text(n) }) }
            }
        }
        OutlinedButton(onClick = { pickZip.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) },
            enabled = s.busy == null) { Text("Open a survey zip...") }
        Status(vm, s)
        s.verify?.let { v ->
            val counts = Verify.ORDER.associateWith { k -> v.items.count { it.verdict == k } }.filterValues { it > 0 }
            Text(v.survey, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(counts.entries.joinToString(" · ") { "${it.value} ${it.key.lowercase()}" } +
                "  (WLC knows ${v.wlcAps} APs)", style = MaterialTheme.typography.bodyMedium)
            for (item in v.items) VerifyCard(item)
            v.csv?.let { f ->
                OutlinedButton(onClick = {
                    val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", f)
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"; putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_SUBJECT, "Survey ${v.survey} verified")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Share results"))
                }) { Text("Share results (CSV)") }
            }
            Text("Checked ${v.time}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TranscriptButton(vm)
    }
}

@Composable
private fun VerifyCard(v: io.github.filmonm.whatsmyap.wlc.VerifyItem) {
    val color = when (v.verdict) {
        "VERIFIED", "CONFIRMED" -> Good
        "MISMATCH" -> Bad
        else -> Warn
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(v.verdict, color = color, fontWeight = FontWeight.Bold)
                Text(v.hit?.ap?.let { ap -> "WLC: $ap" + (v.wlcNumber?.let { " (#$it)" } ?: "") } ?: "not on this WLC",
                    fontWeight = FontWeight.SemiBold)
            }
            val label = listOfNotNull(
                v.ocr?.let { "photo reads ${v.ocrLabel ?: "#$it"}" },
                v.typed?.let { "typed #$it" },
            ).joinToString(" · ").ifEmpty { "no label recorded" }
            Text("Label: $label")
            Text(listOfNotNull("BSSID ${v.bssid}", v.ssid.ifEmpty { null }, v.hit?.how?.takeIf { it == "radio" }?.let { "matched by radio MAC" })
                .joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            Text(listOfNotNull(v.firstSeen, count(v.checks, "check"), v.photo, v.hit?.location?.ifEmpty { null } ?: v.place)
                .joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
