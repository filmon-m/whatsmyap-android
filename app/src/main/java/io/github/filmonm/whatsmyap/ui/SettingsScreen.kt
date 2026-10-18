package io.github.filmonm.whatsmyap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.filmonm.whatsmyap.data.Settings

/** The prototype's settings.ini as a screen. Blank = default (shown greyed in each field). */
@Composable
fun SettingsScreen(vm: FieldViewModel, onBack: () -> Unit) {
    val s = vm.settings
    val keys = listOf("teams_rtt_ms", "teams_jitter_ms", "teams_loss_pct", "teams_relay",
        "speed_24", "speed_5", "speed_6", "internet_order", "local_server")
    val values = remember { mutableStateMapOf<String, String>().apply { keys.forEach { put(it, s.raw(it)) } } }

    @Composable
    fun field(key: String, label: String, default: String, number: Boolean = true) {
        OutlinedTextField(
            value = values[key].orEmpty(),
            onValueChange = { values[key] = it },
            label = { Text(label) },
            placeholder = { Text(default) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    fun num(d: Double) = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Back") }
                Text("Settings", style = MaterialTheme.typography.titleLarge)
            }
            Text("Leave a field blank to use the default shown in it.", style = MaterialTheme.typography.bodySmall)

            Text("Teams check", style = MaterialTheme.typography.titleMedium)
            Text("UDP to a Microsoft Teams media relay; passes when all three are within limits. " +
                "Defaults are Microsoft's targets for the client-to-Microsoft-edge path.", style = MaterialTheme.typography.bodySmall)
            field("teams_rtt_ms", "Round trip at most (ms)", num(Settings.TEAMS_RTT_MS))
            field("teams_jitter_ms", "Jitter at most (ms)", num(Settings.TEAMS_JITTER_MS))
            field("teams_loss_pct", "Packet loss at most (%)", num(Settings.TEAMS_LOSS_PCT))
            field("teams_relay", "Teams relay", Settings.TEAMS_RELAY, number = false)

            Text("Speed test", style = MaterialTheme.typography.titleMedium)
            Text("Average download (Mbps) needed to pass, per band.", style = MaterialTheme.typography.bodySmall)
            field("speed_24", "2.4 GHz", num(Settings.SPEED_24))
            field("speed_5", "5 GHz", num(Settings.SPEED_5))
            field("speed_6", "6 GHz", num(Settings.SPEED_6))
            field("internet_order", "Internet test order (mlab, vultr, cloudflare)", Settings.INTERNET_ORDER, number = false)
            Text("M-Lab is the test behind Google's speed test; its results join M-Lab's public dataset.",
                style = MaterialTheme.typography.bodySmall)

            Text("Local test server", style = MaterialTheme.typography.titleMedium)
            Text("The site's own test server, e.g. the Pi in the MDF, running M-Lab's ndt-server. " +
                "IP address, optionally :port (default ${Settings.LOCAL_PORT}). When set, Speed test asks local or internet.",
                style = MaterialTheme.typography.bodySmall)
            field("local_server", "IP address[:port]", "e.g. 10.20.30.40", number = false)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    keys.forEach { s.set(it, values[it].orEmpty()) }
                    vm.settingsChanged()
                    onBack()
                }) { Text("Save") }
                OutlinedButton(onClick = { keys.forEach { values[it] = "" } }) { Text("Reset to defaults") }
            }
        }
    }
}
