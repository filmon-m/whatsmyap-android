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
import io.github.filmonm.whatsmyap.data.Branding
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onField: () -> Unit, onNetEng: () -> Unit, onResults: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val logo = remember { Branding.bitmap(context)?.asImageBitmap() }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            logo?.let {
                Surface(color = Color.White, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Image(it, contentDescription = "Logo", contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 72.dp).padding(8.dp))
                }
            }
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
            Card(onClick = onNetEng, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("Network Engineer", style = MaterialTheme.typography.titleLarge)
                    Text("Inspect, Debug my AP, Debug my WLC. Needs a WLC login (saved on this tablet with a PIN).",
                        style = MaterialTheme.typography.bodyMedium)
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
