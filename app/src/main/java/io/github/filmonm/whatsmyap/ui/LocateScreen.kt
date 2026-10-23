package io.github.filmonm.whatsmyap.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.filmonm.whatsmyap.core.Rules
import io.github.filmonm.whatsmyap.wlc.Parse
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Survey (locate APs): label, photos and a note per AP, no checks; zipped like a Field Survey. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocateScreen(vm: LocateViewModel, field: FieldViewModel, neteng: NetEngViewModel,
                 onCamera: () -> Unit, onReport: () -> Unit, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val fs by field.state.collectAsStateWithLifecycle()
    val ne by neteng.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var askName by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var packing by remember { mutableStateOf<String?>(null) }
    var label by remember { mutableStateOf("") }
    var note by remember(s.current) { mutableStateOf("") }

    LaunchedEffect(Unit) {
        vm.reload()
        field.refreshLocation()
        while (true) { field.refreshWifi(); delay(5000) }
    }

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
                putExtra(Intent.EXTRA_SUBJECT, "AP locate survey ${s.survey}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Send survey"))
            if (finish) vm.end()
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Back") }
                Text("Survey (locate APs)", style = MaterialTheme.typography.titleLarge)
            }

            // the survey
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.survey == null) {
                        Text("Record where each AP or IDF is: its label, photos and a note. No checks - just a record.")
                        Button(onClick = { askName = true }) { Text("Start a survey") }
                    } else {
                        Text("Survey: ${s.survey}", fontWeight = FontWeight.SemiBold)
                        Text(listOf(count(s.aps.size, "label"), count(s.aps.sumOf { it.photos }, "photo"),
                            count(s.aps.count { it.note != null }, "note")).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { share(finish = true) }) { Text("Finish & send") }
                            OutlinedButton(onClick = { menu = true }) { Text("More") }
                        }
                    }
                    packing?.let { Text(it, style = MaterialTheme.typography.bodySmall); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                }
            }

            // where the tablet is (Inspect's view, for reference only)
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val w = fs.wifi
                    if (w == null) Text(fs.wifiError ?: "Reading Wi-Fi...")
                    else {
                        Text("SSID ${w.ssid.ifBlank { "?" }} · ${Rules.bandLabel(w.band)} ch ${w.channel ?: "?"} · ${w.rssi} dBm",
                            style = MaterialTheme.typography.bodyMedium)
                        Text("BSSID ${w.bssid.ifBlank { "?" }}", style = MaterialTheme.typography.bodySmall)
                        val seen = ne.inspect?.takeIf { it.wifi?.bssid == w.bssid }?.client?.ap
                        if (ne.wlcName != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(seen?.let { "WLC: on $it" + (Parse.apNumber(it)?.let { n -> " (#$n)" } ?: "") }
                                    ?: "WLC: tap Refresh to see which AP", Modifier.weight(1f),
                                    fontWeight = if (seen != null) FontWeight.SemiBold else FontWeight.Normal)
                                TextButton(onClick = neteng::refreshInspect, enabled = ne.busy == null) { Text("Refresh") }
                            }
                            ne.busy?.let { Text(it.text, style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                    Text("Where: " + (fs.place?.text() ?: if (fs.locating) "locating..." else "N/A"), style = MaterialTheme.typography.bodySmall)
                }
            }

            if (s.survey != null) {
                // which AP we're collecting for
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(label, { label = it }, label = { Text("Label, e.g. AP01 or IDF01") }, singleLine = true,
                        modifier = Modifier.weight(1f))
                    Button(onClick = { vm.setAp(label); label = "" }, enabled = label.isNotBlank()) { Text("Start") }
                }
                val cur = s.current
                if (cur == null) {
                    Text("Enter the label of what's in front of you (an AP, an IDF...). Photos and notes then go to it until you start another.",
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    val ap = s.aps.firstOrNull { it.label == cur }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Collecting: $cur", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(count(ap?.photos ?: 0, "photo") + (ap?.note?.let { " · note: $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall)
                            Button(onClick = onCamera) { Text("Take photos") }
                            OutlinedTextField(note, { note = it }, label = { Text("Note, e.g. in Room 12, not Room 11") },
                                modifier = Modifier.fillMaxWidth(), minLines = 2)
                            OutlinedButton(onClick = { vm.saveNote(note); note = "" }, enabled = note.isNotBlank()) { Text("Save note") }
                        }
                    }
                }
                s.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

                // everything collected so far; tap one to add to it
                if (s.aps.isNotEmpty()) {
                    Text("In this survey", fontWeight = FontWeight.SemiBold)
                    s.aps.forEach { ap ->
                        OutlinedCard(onClick = { vm.setAp(ap.label) }, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(ap.label, fontWeight = FontWeight.SemiBold)
                                    Text(count(ap.photos, "photo") + (ap.note?.let { " · $it" } ?: ""),
                                        style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                }
                                if (ap.label == s.current) Text("current", color = Good, style = MaterialTheme.typography.labelSmall)
                                else Spacer(Modifier)
                            }
                        }
                    }
                }
            }
        }
    }

    if (askName) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askName = false },
            title = { Text("Start a survey") },
            text = {
                Column {
                    Text("Labels, photos and notes go into this survey's own file, packed into one zip at the end.")
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                        label = { Text("Name, e.g. Maple_AP_Locate") })
                }
            },
            confirmButton = { TextButton(onClick = { vm.start(name); askName = false }, enabled = name.isNotBlank()) { Text("Start") } },
            dismissButton = { TextButton(onClick = { askName = false }) { Text("Cancel") } },
        )
    }
    if (menu) {
        AlertDialog(
            onDismissRequest = { menu = false },
            title = { Text("Survey ${s.survey}") },
            text = { Text("Send a zip now and keep going, view the report, or end the survey without sending.") },
            confirmButton = {
                Column {
                    TextButton(onClick = { menu = false; share(finish = false) }) { Text("Send zip, keep surveying") }
                    TextButton(onClick = { menu = false; onReport() }) { Text("View report") }
                    TextButton(onClick = { menu = false; vm.end() }) { Text("End without sending") }
                }
            },
            dismissButton = { TextButton(onClick = { menu = false }) { Text("Close") } },
        )
    }
}
