package io.github.filmonm.whatsmyap.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import io.github.filmonm.whatsmyap.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Survey mode: a named job (e.g. Maple_Wifi_Tests) with its own clean CSV, packaged as
 * zip(s) to share. Survives app restarts until finished.
 *
 *   <name>/<name>.csv     this survey's checks only
 *   <name>/photos/        label photos at review size (1600 px); originals stay on the tablet
 *   <name>/report.html    the dashboard with the survey built in - opens offline
 *   <name>/manifest.txt   SHA-256 of every file, which part it's in
 */
class Survey(private val context: Context) {

    companion object {
        const val REVIEW_PX = 1600
        const val REVIEW_QUALITY = 75
        const val MAX_ZIP_BYTES = 20_000_000L
        fun cleanName(name: String) =
            name.trim().replace(Regex("[^A-Za-z0-9_.-]+"), "_").trim('.', '_').take(60)
    }

    private val prefs = context.getSharedPreferences("survey", Context.MODE_PRIVATE)

    val active: String? get() = prefs.getString("name", null)
    val started: String? get() = prefs.getString("started", null)

    fun dir(name: String) = File(Storage.surveys(context), name).apply { mkdirs() }
    fun csv(name: String) = File(dir(name), "$name.csv")

    /** -> true if it resumed an existing survey of that name. */
    fun start(name: String): Boolean {
        val n = cleanName(name)
        require(n.isNotEmpty()) { "A survey needs a name (letters, numbers, _ - .)" }
        val resumed = csv(n).exists()
        val first = if (resumed) CsvLog.read(csv(n)).firstOrNull()?.get("timestamp") else null
        prefs.edit().putString("name", n).putString("started", first ?: Storage.timestamp()).apply()
        return resumed
    }

    fun stop() = prefs.edit().remove("name").remove("started").apply()

    data class Stats(val checks: Int, val aps: Int, val photos: Int, val tests: Int)

    fun stats(name: String): Stats {
        val rows = CsvLog.read(csv(name))
        val aps = rows.mapNotNull { r ->
            r["ap_name"]?.ifBlank { null } ?: r["ap_number"]?.ifBlank { null }?.let { "AP #$it" }
            ?: r["bssid"]?.take(14)?.ifBlank { null }
        }.toSet()
        return Stats(rows.size, aps.size, rows.mapNotNull { it["photo_file"]?.ifBlank { null } }.toSet().size,
            rows.count { !it["speed_avg_mbps"].isNullOrBlank() })
    }

    // ---------------------------------------------------------------- packaging
    private fun findPhoto(row: Map<String, String>): File? {
        val name = row["photo_file"] ?: return null
        val month = (row["photo_taken"]?.ifBlank { null } ?: row["timestamp"].orEmpty()).take(7)
        File(Storage.photos(context, month), name).takeIf { it.exists() }?.let { return it }
        return File(Storage.root(context), "photos").walkTopDown().firstOrNull { it.name == name }
    }

    /** Review-size JPEG, upright. */
    private fun shrink(file: File): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= REVIEW_PX) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return file.readBytes()
        val scale = REVIEW_PX.toFloat() / maxOf(bmp.width, bmp.height)
        val rotation = when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val m = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (rotation != 0f) postRotate(rotation)
        }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, REVIEW_QUALITY, it) }.toByteArray()
    }

    /** The dashboard page with this survey's rows built in (all dates shown). */
    fun reportHtml(name: String, rows: List<Map<String, String>>): String =
        Results.page(context, name, rows, label = null, range = "all")

    /** Writes report.html + review-size photos into the survey folder (for viewing in the app). */
    fun stage(name: String): File {
        val d = dir(name)
        val rows = CsvLog.read(csv(name))
        val pdir = File(d, "photos").apply { mkdirs() }
        rows.filter { !it["photo_file"].isNullOrBlank() }.distinctBy { it["photo_file"] }.forEach { r ->
            val target = File(pdir, r["photo_file"]!!)
            if (!target.exists()) findPhoto(r)?.let { src -> runCatching { target.writeBytes(shrink(src)) } }
        }
        return File(d, "report.html").apply { writeText(reportHtml(name, rows)) }
    }

    data class Packaged(val files: List<File>, val bytes: Long, val photos: Int, val missing: Int)

    /** Build <name>.zip, or <name>_part1of3.zip ... when bigger than MAX_ZIP_BYTES. Part 1 has the CSV,
     *  report and manifest; all parts unzip into the same <name>/ folder. */
    fun packageZips(name: String, progress: (String) -> Unit = {}): Packaged {
        val rows = CsvLog.read(csv(name))
        val csvBytes = csv(name).readBytes()
        val report = reportHtml(name, rows).toByteArray()
        val out = Storage.share(context)
        out.listFiles()?.filter { it.name.startsWith(name) && it.name.endsWith(".zip") }?.forEach { it.delete() }

        class Part(val file: File) {
            val zip = ZipOutputStream(FileOutputStream(file))
            var bytes = 0L
            var photos = 0
        }
        val parts = mutableListOf<Part>()
        val manifest = mutableListOf<String>()
        fun newPart() = Part(File(out, "${name}_part${parts.size + 1}.tmp")).also { parts += it }
        fun add(p: Part, path: String, data: ByteArray, note: String = "") {
            p.zip.putNextEntry(ZipEntry("$name/$path"))
            p.zip.write(data)
            p.zip.closeEntry()
            p.bytes += data.size
            manifest += "%s  %9d  part %d  %s%s".format(Storage.sha256(data), data.size, parts.indexOf(p) + 1, path,
                if (note.isNotEmpty()) "  ($note)" else "")
        }

        var cur = newPart()
        cur.bytes = csvBytes.size + report.size + 200_000L       // room for CSV, report, manifest
        val photoRows = rows.filter { !it["photo_file"].isNullOrBlank() }.distinctBy { it["photo_file"] }.sortedBy { it["photo_file"] }
        var missing = 0
        photoRows.forEachIndexed { i, r ->
            val fname = r["photo_file"]!!
            progress("Photo ${i + 1}/${photoRows.size}: $fname")
            val src = findPhoto(r)
            if (src == null) {
                missing++
                manifest += "-  0  part 0  photos/$fname  (MISSING on tablet)"
                return@forEachIndexed
            }
            val data = runCatching { shrink(src) }.getOrElse { src.readBytes() }
            if (cur.photos > 0 && cur.bytes + data.size > MAX_ZIP_BYTES) cur = newPart()
            add(cur, "photos/$fname", data, "review size; original SHA-256 in CSV")
            cur.photos++
        }
        progress("Survey CSV and report")
        val first = parts.first()
        add(first, "$name.csv", csvBytes)
        add(first, "report.html", report)
        val st = stats(name)
        val n = parts.size
        val head = listOf(
            "Survey:    $name",
            "Started:   ${started ?: rows.firstOrNull()?.get("timestamp") ?: "?"}",
            "Packaged:  ${Storage.timestamp()}",
            "App:       What's My AP ${BuildConfig.VERSION_NAME} (Android)",
            "Contents:  " + listOf(st.checks to "check", st.aps to "AP", st.tests to "speed test", st.photos to "photo")
                .joinToString(", ") { (n, w) -> "$n $w" + if (n == 1) "" else "s" },
            "Zip files: $n" + if (n > 1) "  - unzip ALL $n parts into the same place, then open report.html" else "",
            "",
            "Open report.html in a browser to review. photos/ are review-size copies; the",
            "photo_sha256 column in the CSV is the fingerprint of each ORIGINAL photo.",
            "",
            "SHA-256 of each file, size, part, file:",
            "",
        )
        first.zip.putNextEntry(ZipEntry("$name/manifest.txt"))
        first.zip.write((head + manifest).joinToString("\n", postfix = "\n").toByteArray())
        first.zip.closeEntry()
        val files = parts.mapIndexed { k, p ->
            p.zip.close()
            val final = File(out, if (n == 1) "$name.zip" else "${name}_part${k + 1}of$n.zip")
            p.file.renameTo(final)
            final
        }
        return Packaged(files, files.sumOf { it.length() }, photoRows.size - missing, missing)
    }
}
