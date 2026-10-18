package io.github.filmonm.whatsmyap.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

/** The web dashboard in a WebView: the running survey's report, or every check on the tablet (all = true). */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReportScreen(vm: FieldViewModel, onBack: () -> Unit, all: Boolean = false) {
    var file by remember { mutableStateOf<File?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        file = runCatching { if (all) vm.stageResults() else vm.stageReport() }.getOrNull()
        failed = file == null
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TextButton(onClick = onBack, modifier = Modifier.padding(4.dp)) { Text("‹ Back") }
        when {
            failed -> Text(if (all) "Could not build the results page." else "No survey running - start one to see its report.", Modifier.padding(16.dp))
            file == null -> Text("Preparing the report...", Modifier.padding(16.dp))
            else -> AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = true          // the page + photos next to it
                        settings.domStorageEnabled = true
                    }
                },
                update = { it.loadUrl("file://" + file!!.absolutePath) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
