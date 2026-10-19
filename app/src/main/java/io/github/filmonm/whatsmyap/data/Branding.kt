package io.github.filmonm.whatsmyap.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.File

/**
 * Optional header logo, chosen on the tablet in Settings. It lives only on the tablet (never in the app's
 * source), and shows on the home screen and in survey reports / Results.
 */
object Branding {
    private const val MAX_W = 1200

    fun file(context: Context) = File(context.filesDir, "branding/logo.png")

    /** Copy the picked image in (scaled down, PNG). false if it isn't a readable image. */
    fun set(context: Context, uri: Uri): Boolean {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
        if (bmp.width > MAX_W) bmp = Bitmap.createScaledBitmap(bmp, MAX_W, bmp.height * MAX_W / bmp.width, true)
        val f = file(context).apply { parentFile?.mkdirs() }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return true
    }

    fun clear(context: Context) { file(context).delete() }

    fun bitmap(context: Context): Bitmap? = file(context).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    /** For the report page, which has to stand alone. */
    fun dataUrl(context: Context): String? {
        val f = file(context).takeIf { it.exists() } ?: return null
        return "data:image/png;base64," + Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
    }
}
