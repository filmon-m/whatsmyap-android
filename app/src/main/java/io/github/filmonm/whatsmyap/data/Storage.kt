package io.github.filmonm.whatsmyap.data

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where everything lives: the app's own storage (no storage-wide permission needed). */
object Storage {
    fun root(context: Context): File = context.getExternalFilesDir(null) ?: context.filesDir
    fun logs(context: Context) = File(root(context), "logs").apply { mkdirs() }
    fun photos(context: Context, month: String) = File(root(context), "photos/$month").apply { mkdirs() }
    fun surveys(context: Context) = File(root(context), "surveys").apply { mkdirs() }
    fun share(context: Context) = File(context.cacheDir, "share").apply { mkdirs() }

    fun month(d: Date = Date()): String = SimpleDateFormat("yyyy-MM", Locale.US).format(d)
    fun timestamp(d: Date = Date()): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(d)
    fun fileStamp(d: Date = Date()): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(d)

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_.-]+"), "-").trim('-').take(40)
}

/** A label photo, fingerprinted when it was taken. */
data class Photo(
    val file: File,
    val sha256: String,
    val taken: String,
    val apKey: String,             // the AP it belongs to (radio MAC prefix)
    val ocr: LabelReader.Result?,  // null = unreadable (or OCR didn't run)
    val ocrRan: Boolean,
) {
    fun columns(): Map<String, Any?> = buildMap {
        put("photo_file", file.name)
        put("photo_sha256", sha256)
        put("photo_taken", taken)
        if (ocrRan) {
            put("ocr_label", ocr?.text ?: "UNREADABLE")
            put("ocr_number", ocr?.number)
            put("ocr_secs", ocr?.let { "%.1f".format(it.ms / 1000.0) })
        }
    }
}
