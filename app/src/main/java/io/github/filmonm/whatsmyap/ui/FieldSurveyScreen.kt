package io.github.filmonm.whatsmyap.ui

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.filmonm.whatsmyap.core.Labels
import io.github.filmonm.whatsmyap.core.Rules
import io.github.filmonm.whatsmyap.data.Images
import io.github.filmonm.whatsmyap.data.SpeedTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val Good = Color(0xFF0CA30C)
val Warn = Color(0xFFB27A00)
val Bad = Color(0xFFD03B3B)

/** "1 photo", "2 photos". */
fun count(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"

fun statusColor(s: Rules.Status?) = when (s) {
    Rules.Status.PASS -> Good
    Rules.Status.WARN -> Warn
    Rules.Status.FAIL -> Bad
    null -> Color.Unspecified
}

@Composable
fun FieldSurveyScreen(vm: FieldViewModel, onCamera: () -> Unit, onReport: () -> Unit, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val perms = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.refreshWifi(); vm.refreshLocation(force = true)
    }
    LaunchedEffect(Unit) { launcher.launch(perms.toTypedArray()) }
    LaunchedEffect(Unit) {                       // keep the Wi-Fi details live while this screen is open
        while (true) { vm.refreshWifi(); delay(5000) }
    }

    var askName by remember { mutableStateOf(false) }
    var askTarget by remember { mutableStateOf(false) }
    var surveyMenu by remember { mutableStateOf(false) }
    var packing by remember { mutableStateOf<String?>(null) }

    fun share(finish: Boolean) {
        scope.launch {
            packing = "Packing the survey"
            val p = vm.packageSurvey { packing = it }
            packing = null
            if (p == null) return@launch
            val uris = ArrayList(p.files.map { FileProvider.getUriForFile(context, context.packageName + ".files", it) })
            val send = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                type = "application/zip"
                if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                putExtra(Intent.EXTRA_SUBJECT, "Wi-Fi survey ${s.survey}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Send survey"))
            if (finish) vm.endSurvey()
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Home") }
                Text("Field Survey", style = MaterialTheme.typography.titleLarge)
            }

            // survey banner
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.survey == null) {
                        Text("No survey running. Checks still go to the normal log.")
                        Button(onClick = { askName = true }) { Text("Start a survey") }
                    } else {
                        val st = s.surveyStats
                        Text("Survey: ${s.survey}", fontWeight = FontWeight.SemiBold)
                        if (st != null) Text(listOf(count(st.checks, "check"), count(st.aps, "AP"), count(st.tests, "speed test"), count(st.photos, "photo")).joinToString(" \u00b7 "),
                            style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { share(finish = true) }) { Text("Finish & send") }
                            OutlinedButton(onClick = { surveyMenu = true }) { Text("More") }
                        }
                    }
                    packing?.let { Text(it, style = MaterialTheme.typography.bodySmall); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                }
            }

            // what the tablet sees
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val w = s.wifi
                    if (w == null) Text(s.wifiError ?: "Reading Wi-Fi...")
                    else {
                        Text("SSID:   ${w.ssid.ifBlank { "? (turn Location on)" }}")
                        Text("BSSID:  ${w.bssid.ifBlank { "?" }}")
                        Text("Band:   ${Rules.bandLabel(w.band)}   Channel: ${w.channel ?: "?"}")
                        Text("RSSI:   ${w.rssi} dBm   Link rate: ${w.linkMbps ?: "?"} Mbps")
                        Text("IP:     ${w.ip}" + if (w.gateway.isNotBlank()) "   GW ${w.gateway}" else "")
                        s.wifiError?.let { Text(it, color = Warn) }
                    }
                    Text("Where:  " + (s.place?.text() ?: if (s.locating) "locating..." else "N/A"),
                        style = MaterialTheme.typography.bodySmall)
                }
            }

            OutlinedTextField(
                value = s.tag?.toString() ?: "",
                onValueChange = vm::setTag,
                label = { Text("AP number on the label (optional)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCamera, enabled = s.wifi != null) { Text("Label photo") }
                if (s.testing == null) Button(onClick = {
                    if (vm.settings.localHostPort() != null) askTarget = true else vm.runSpeedTest()
                }, enabled = s.wifi != null) { Text("Speed test") }
                else OutlinedButton(onClick = vm::cancelSpeedTest) { Text("Stop test") }
            }

            s.testing?.let { t ->
                Column {
                    Text(t.phase + (t.mbps?.let { "  %.0f Mbps".format(it) } ?: ""), style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { t.fraction }, modifier = Modifier.fillMaxWidth())
                }
            }

            PhotoCard(vm, s)
            SpeedCard(vm, s)

            s.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }

    if (askName) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askName = false },
            title = { Text("Start a survey") },
            text = {
                Column {
                    Text("Everything you check from now on also goes into this survey's own file, packed into one zip at the end.")
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                        label = { Text("Name, e.g. Braddock_Wifi_Tests") })
                }
            },
            confirmButton = { TextButton(onClick = { vm.startSurvey(name); askName = false }, enabled = name.isNotBlank()) { Text("Start") } },
            dismissButton = { TextButton(onClick = { askName = false }) { Text("Cancel") } },
        )
    }
    if (askTarget) {
        AlertDialog(
            onDismissRequest = { askTarget = false },
            title = { Text("Speed test to...") },
            text = { Text("Local: the site's test server (${vm.settings.localHostPort()}), the SOP path.\nInternet: Teams check + M-Lab / Vultr.") },
            confirmButton = {
                TextButton(onClick = { askTarget = false; vm.runSpeedTest(SpeedTest.Target.LOCAL) }) { Text("Local server") }
            },
            dismissButton = {
                TextButton(onClick = { askTarget = false; vm.runSpeedTest(SpeedTest.Target.INTERNET) }) { Text("Internet") }
            },
        )
    }
    if (surveyMenu) {
        AlertDialog(
            onDismissRequest = { surveyMenu = false },
            title = { Text("Survey ${s.survey}") },
            text = { Text("Send a zip now and keep going, view the report, or end the survey without sending.") },
            confirmButton = {
                Column {
                    TextButton(onClick = { surveyMenu = false; share(finish = false) }) { Text("Send zip, keep surveying") }
                    TextButton(onClick = { surveyMenu = false; onReport() }) { Text("View report") }
                    TextButton(onClick = { surveyMenu = false; vm.endSurvey() }) { Text("End without sending") }
                }
            },
            dismissButton = { TextButton(onClick = { surveyMenu = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun PhotoCard(vm: FieldViewModel, s: FieldState) {
    val p = vm.photoHere(s)
    if (s.readingLabel) { Text("Reading the label..."); LinearProgressIndicator(Modifier.fillMaxWidth()); return }
    if (p == null) {
        if (s.photo != null) Text("The last label photo was at another AP - take one for this AP.", color = Warn)
        return
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val thumb = remember(p.file) {
                runCatching {
                    Images.thumbnail(p.file)?.asImageBitmap()
                }.getOrNull()
            }
            thumb?.let { Image(it, contentDescription = "Label photo", contentScale = ContentScale.Crop, modifier = Modifier.size(96.dp)) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Photo ✓ ${p.file.name}", style = MaterialTheme.typography.bodySmall)
                val o = p.ocr
                val v = Labels.verdict(null, s.tag, o?.number)
                when {
                    o == null -> Text("Label unreadable - check the photo", color = Warn)
                    v == Labels.Verdict.CONFLICT -> Text("Label reads ${o.text} ≠ typed #${s.tag}", color = Bad)
                    v == Labels.Verdict.CONSISTENT -> Text("Label reads ${o.text} = typed #${s.tag}", color = Good)
                    else -> Text("Label reads ${o.text} (#${o.number})", fontWeight = FontWeight.SemiBold)
                }
                Text("SHA-256 ${p.sha256.take(16)}…", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SpeedCard(vm: FieldViewModel, s: FieldState) {
    val sp = vm.speedHere(s) ?: return
    val r = sp.result
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val tried = r.attempts.size > 1                // a fallback was needed: show the whole chain
            if (!r.ok) {
                Text("Speed test failed", color = Bad, fontWeight = FontWeight.SemiBold)
                r.attempts.ifEmpty { listOf(r.error.orEmpty()) }.forEach {
                    Text(it, color = Bad, style = MaterialTheme.typography.bodySmall)
                }
                return@Column
            }
            val th = Rules.thresholds[sp.band]
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("%.0f / %.0f / %.0f Mbps".format(r.min, r.avg, r.max), color = statusColor(sp.status),
                    style = MaterialTheme.typography.titleMedium)
                Text(th?.let { "(>${it.toInt()})" } ?: "(band unknown)", style = MaterialTheme.typography.bodySmall)
                val t = sp.teams
                when {
                    t == null -> Text("Teams ?", style = MaterialTheme.typography.bodySmall)
                    t.pass -> Text("Teams ✓", color = Good, fontWeight = FontWeight.SemiBold)
                    else -> Text("Teams ✕", color = Bad, fontWeight = FontWeight.SemiBold)
                }
            }
            val details = listOfNotNull(
                "min/avg/max, %.0f MB in %.0f s".format(r.mb, r.secs),
                ((if (r.site == "mlab") "M-Lab" else r.site) + if (r.colo.isNotBlank()) " ${r.colo}" else "").ifBlank { null },
                r.upMbps?.let { "Up %.0f Mbps".format(it) },
                r.latencyMs?.let { "Latency %.0f ms".format(it) + (r.loadedLatencyMs?.let { l -> " (%.0f loaded)".format(l) } ?: "") },
                r.jitterMs?.let { "Jitter %.0f ms".format(it) },
                r.retransPct?.let { "Retransmits %.1f%%".format(it) },
            )
            Text(details.joinToString(" \u00b7 "), style = MaterialTheme.typography.bodySmall)
            val probe = r.teams
            when {
                probe != null && probe.ok -> Text(
                    "Teams (UDP to Microsoft relay): RTT %.0f ms \u00b7 jitter %.1f ms \u00b7 loss %.1f%% (%d of %d answered)"
                        .format(probe.rttMs, probe.jitterMs, probe.lossPct, probe.received, probe.sent),
                    style = MaterialTheme.typography.bodySmall)
                probe != null -> Text("Teams relay: ${probe.error}" +
                    if (sp.teamsSource == "tcp-estimate") " - verdict estimated from the download test" else "",
                    color = Warn, style = MaterialTheme.typography.bodySmall)
            }
            sp.teams?.takeIf { !it.pass }?.let { Text("Teams: ${it.why}", color = Bad, style = MaterialTheme.typography.bodySmall) }
            if (sp.roamed) Text("Roamed during the test", color = Warn)
            if (tried) Text("Tried: " + r.attempts.joinToString(" \u2192 "), color = Warn, style = MaterialTheme.typography.bodySmall)
        }
    }
}
