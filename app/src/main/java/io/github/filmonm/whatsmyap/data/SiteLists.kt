package io.github.filmonm.whatsmyap.data

import android.content.Context
import android.net.Uri
import io.github.filmonm.whatsmyap.wlc.BssHit
import io.github.filmonm.whatsmyap.wlc.BssTable
import io.github.filmonm.whatsmyap.wlc.Parse
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Site AP lists: a controller's BSSID -> AP table, saved by an engineer (Network Engineer mode) and loaded onto
 * testers' tablets, so a tester without a WLC login sees which AP they're on. Every loaded list is searched,
 * so the right site picks itself.
 */
object SiteLists {

    data class Info(val file: File, val name: String, val vendor: String, val created: String, val aps: Int) {
        val ageDays: Long? get() = runCatching {
            val t = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(created)!!.time
            (System.currentTimeMillis() - t) / 86_400_000L
        }.getOrNull()
    }

    /** A hit and the list it came from. */
    data class Hit(val hit: BssHit, val list: Info) {
        val number get() = Parse.apNumber(hit.ap)
    }

    private var cache: List<Pair<Info, BssTable>>? = null

    fun dir(context: Context) = File(context.filesDir, "sitelists").apply { mkdirs() }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_.-]+"), "_").take(60).ifEmpty { "site" }

    @Synchronized
    fun all(context: Context): List<Pair<Info, BssTable>> = cache ?: (dir(context).listFiles { f -> f.name.endsWith(".csv") }.orEmpty()
        .mapNotNull { f ->
            runCatching {
                val (meta, table) = BssTable.fromCsv(f.readText())
                Info(f, meta["name"] ?: f.nameWithoutExtension, meta["vendor"] ?: "", meta["created"] ?: "", table.aps) to table
            }.getOrNull()
        }.sortedBy { it.first.name }).also { cache = it }

    fun infos(context: Context) = all(context).map { it.first }

    /** Save from Network Engineer mode (replaces that controller's older list). -> the file, to share. */
    @Synchronized
    fun save(context: Context, wlcName: String, vendor: String, table: BssTable): File {
        val f = File(dir(context), safe(wlcName) + ".csv")
        f.writeText(table.toCsv(wlcName, vendor, Storage.timestamp()))
        cache = null
        return f
    }

    /** Load a list someone shared. -> its info, or null if the file isn't a site list. */
    @Synchronized
    fun import(context: Context, uri: Uri): Info? {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return null
        if (!text.startsWith("# What's My AP site list")) return null
        val (meta, table) = BssTable.fromCsv(text)
        if (table.isEmpty) return null
        val name = meta["name"] ?: "site"
        File(dir(context), safe(name) + ".csv").writeText(text)
        cache = null
        return Info(File(dir(context), safe(name) + ".csv"), name, meta["vendor"] ?: "", meta["created"] ?: "", table.aps)
    }

    @Synchronized
    fun remove(info: Info) { info.file.delete(); cache = null }

    fun find(context: Context, bssid: String?): Hit? {
        if (bssid.isNullOrEmpty()) return null
        for ((info, table) in all(context)) table.find(bssid)?.let { return Hit(it, info) }
        return null
    }
}
