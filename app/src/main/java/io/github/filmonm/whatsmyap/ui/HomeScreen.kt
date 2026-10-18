package io.github.filmonm.whatsmyap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.filmonm.whatsmyap.BuildConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onField: () -> Unit, onResults: () -> Unit, onSettings: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("What's My AP", style = MaterialTheme.typography.headlineMedium)
            Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Card(onClick = onField, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("Field Survey", style = MaterialTheme.typography.titleLarge)
                    Text("Label photo and OCR, speed test, location, survey zip. No login needed.",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("Network Engineer", style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Inspect, Debug my AP, Debug my WLC, Verify a survey. Unlocked by WLC credentials - coming in phase 2.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedCard(onClick = onResults, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("Results", style = MaterialTheme.typography.titleLarge)
                    Text("Dashboard of every check on this tablet - Overview, APs, speed and Teams. Opens on Today.",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            TextButton(onClick = onSettings) { Text("Settings") }
        }
    }
}
