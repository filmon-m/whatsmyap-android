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

/** The survey report (the prototype's dashboard with the survey built in), shown in a WebView. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReportScreen(vm: FieldViewModel, onBack: () -> Unit) {
    var file by remember { mutableStateOf<File?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        file = runCatching { vm.stageReport() }.getOrNull()
        failed = file == null
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TextButton(onClick = onBack, modifier = Modifier.padding(4.dp)) { Text("‹ Back") }
        when {
            failed -> Text("No survey running - start one to see its report.", Modifier.padding(16.dp))
            file == null -> Text("Preparing the report...", Modifier.padding(16.dp))
            else -> AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = true          // report.html + photos/ in the survey folder
                        settings.domStorageEnabled = true
                    }
                },
                update = { it.loadUrl("file://" + file!!.absolutePath) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
