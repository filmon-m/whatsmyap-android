package io.github.filmonm.whatsmyap.data

import android.content.Context
import io.github.filmonm.whatsmyap.BuildConfig
import io.github.filmonm.whatsmyap.core.Rules
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The web dashboard: every check in the monthly logs, or one survey's rows (see Survey). */
object Results {

    /** The dashboard page with rows built in. range: "1" = today, "7", "30", "all". */
    fun page(context: Context, name: String, rows: List<Map<String, String>>, label: String?, range: String): String {
        val page = context.assets.open("dashboard.html").bufferedReader().use { it.readText() }
        val embed = JSONObject().apply {
            put("name", name)
            if (label != null) put("label", label)
            put("range", range)
            put("rows", JSONArray(rows.map { JSONObject(it as Map<*, *>) }))
            put("version", BuildConfig.VERSION_NAME)
            put("thresholds", JSONObject(Rules.thresholds as Map<*, *>))
            put("limits", JSONObject().put("teams", JSONObject().put("rtt_ms", Rules.teamsRttMs).put("jitter_ms", Rules.teamsJitterMs)))
            put("photoDir", "photos")
            Branding.dataUrl(context)?.let { put("logo", it) }
        }
        val data = embed.toString().replace("</", "<\\/")
        return page.replaceFirst("<script>", "<script>window.EMBED = $data;</script>\n<script>")
    }

    /** All monthly logs -> results.html next to logs/ and photos/, so photos load from where they live. */
    fun stage(context: Context): File {
        val root = Storage.root(context)
        val rows = (Storage.logs(context).listFiles { f -> f.name.startsWith("whatsmyap_") && f.name.endsWith(".csv") }
            ?: emptyArray()).sortedBy { it.name }.flatMap { CsvLog.read(it) }
            .map { r ->
                val name = r["photo_file"]?.ifBlank { null } ?: return@map r
                val month = (r["photo_taken"]?.ifBlank { null } ?: r["timestamp"].orEmpty()).take(7)
                if (File(root, "photos/$month/$name").exists()) r + ("photo_path" to "photos/$month/$name") else r
            }
        return File(root, "results.html").apply {
            writeText(page(context, "All results", rows, label = "this tablet, ${rows.size} check" + (if (rows.size == 1) "" else "s"), range = "1"))
        }
    }
}
