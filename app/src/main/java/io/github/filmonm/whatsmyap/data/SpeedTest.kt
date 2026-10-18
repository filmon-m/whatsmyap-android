package io.github.filmonm.whatsmyap.data

import android.content.Context
import io.github.filmonm.whatsmyap.BuildConfig
import io.github.filmonm.whatsmyap.core.Rules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Result of one speed test. Data is counted in memory and dropped - nothing is written to disk. */
data class SpeedResult(
    val min: Double? = null,
    val avg: Double? = null,
    val max: Double? = null,
    val mb: Double = 0.0,
    val secs: Double = 0.0,
    val upMbps: Double? = null,
    val latencyMs: Double? = null,
    val loadedLatencyMs: Double? = null,
    val jitterMs: Double? = null,
    val site: String = "",
    val colo: String = "",
    val error: String? = null,
    val httpStatus: Int? = null,
    val rateLimited: Boolean = false,
    val aborted: Boolean = false,
    /** Every server tried, in order, with its outcome: "cloudflare: HTTP 403", "vultr-lax: ok". */
    val attempts: List<String> = emptyList(),
    /** Retransmitted bytes as % of sent (ndt7 only: the server's TCP view). */
    val retransPct: Double? = null,
    /** "internet" or "local" (the site's test server, e.g. the Pi in the MDF). */
    val target: String = "internet",
    /** Teams readiness over UDP to a Microsoft media relay (internet tests only). */
    val teams: TeamsProbe.Result? = null,
) {
    val ok get() = avg != null
}

/** Progress for the screen: phase text, 0..1, the latest per-second Mbps. */
data class SpeedProgress(val phase: String, val fraction: Float, val mbps: Double? = null)

class SpeedTest(private val context: Context) {

    companion object {
        const val CLOUDFLARE = "https://speed.cloudflare.com"
        val FALLBACKS = listOf(
            "http://lax-ca-us-ping.vultr.com/vultr.com.1000MB.bin",
            "http://sjo-ca-us-ping.vultr.com/vultr.com.1000MB.bin",
            "http://wa-us-ping.vultr.com/vultr.com.1000MB.bin",
        )
        const val DOWN_SECONDS = 10.0
        const val UP_SECONDS = 5.0
        const val URL_MAX_SECONDS = 20.0
        const val URL_MAX_BYTES = 200_000_000L
        const val COOLDOWN_S = 75          // each Vultr server allows ~1 test a minute per address
        private const val DOWN_CHUNK = 25_000_000
        private const val UP_CHUNK = 5_000_000
    }

    /** Present ourselves like the prototype did (same name, HTTP/1.1, no compression): a generic
     *  "okhttp" client on HTTP/2 gets scored as a bot by Cloudflare and refused with 403. */
    private val userAgent = "WhatsMyAP/" + BuildConfig.VERSION_NAME
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_1_1))
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .header("User-Agent", userAgent)
                .header("Accept-Encoding", "identity")
                .build())
        }
        .build()
    private val prefs = context.getSharedPreferences("speedtest", Context.MODE_PRIVATE)

    /** Cloudflare first; if it fails, the fallback servers in order, skipping any used in the last minute. */
    enum class Target { INTERNET, LOCAL }

    /**
     * INTERNET: the Teams check (UDP to a Microsoft relay), then throughput from the engines in
     *           Settings order (default M-Lab, then Vultr, then Cloudflare).
     * LOCAL:    throughput to the site's own ndt7 server (Settings > local test server).
     */
    suspend fun run(target: Target, progress: (SpeedProgress) -> Unit): SpeedResult = withContext(Dispatchers.IO) {
        val settings = Settings(context)
        http.connectionPool.evictAll()            // fresh connections every test: no stale keep-alives
        val attempts = mutableListOf<String>()
        fun note(r: SpeedResult) = attempts.add("${r.site}: " + if (r.ok) "ok" else (r.error ?: "failed"))
        fun guarded(site: String, e: Throwable): SpeedResult {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return SpeedResult(site = site, error = e.message ?: e.javaClass.simpleName)
        }

        if (target == Target.LOCAL) {
            val hp = settings.localHostPort()
                ?: return@withContext SpeedResult(site = "local", target = "local", error = "no local test server set (Settings)")
            val r = runCatching { ndt7(Ndt7().local(hp), "local $hp", progress) }.getOrElse { guarded("local $hp", it) }
            note(r)
            return@withContext r.copy(attempts = attempts, target = "local")
        }

        progress(SpeedProgress("Teams check: UDP to the Microsoft relay", 0f))
        val teams = runCatching {
            TeamsProbe.run(settings.teamsRelay, Settings.TEAMS_PROBE_SECONDS) { f ->
                progress(SpeedProgress("Teams check: UDP to the Microsoft relay", f))
            }
        }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            TeamsProbe.Result(settings.teamsRelay, 0, 0, null, null, null, e.message ?: "failed")
        }

        var last: SpeedResult? = null
        for (engine in settings.internetOrder) {
            if (attempts.isNotEmpty()) progress(SpeedProgress("${attempts.last()} - trying $engine", 0f))
            val r = when (engine) {
                "mlab" -> runCatching { ndt7(Ndt7().locate(), "mlab", progress) }.getOrElse { guarded("mlab", it) }
                "cloudflare" -> runCatching { cloudflare(progress) }.getOrElse { guarded("cloudflare", it) }
                else -> vultr(progress, ::note)
            }
            if (engine != "vultr") note(r)
            last = r
            if (r.ok || r.aborted) break
        }
        (last ?: SpeedResult(error = "no test engine configured")).copy(attempts = attempts, teams = teams)
    }

    /** One ndt7 test (M-Lab or local): download, then upload. */
    private suspend fun ndt7(server: Ndt7.Server, site: String, progress: (SpeedProgress) -> Unit): SpeedResult {
        val ndt = Ndt7()
        val label = if (site == "mlab") "M-Lab ${server.name}" else site
        val down = ndt.download(server.download) { f, mbps -> progress(SpeedProgress("Download ($label)", f, mbps)) }
        if (down.error != null && down.bytes == 0L) {
            return SpeedResult(site = site, colo = server.name, error = down.error)
        }
        val up = ndt.upload(server.upload) { f -> progress(SpeedProgress("Upload ($label)", f)) }
        val avg = if (down.samples.isNotEmpty()) down.samples.average()
            else if (down.secs > 0 && down.bytes > 0) down.bytes * 8 / down.secs / 1e6 else null
        return SpeedResult(
            min = down.samples.minOrNull() ?: avg, avg = avg, max = down.samples.maxOrNull() ?: avg,
            mb = down.bytes / 1e6, secs = down.secs,
            upMbps = if (up.bytes > 0 && up.secs > 0) up.bytes * 8 / up.secs / 1e6 else null,
            latencyMs = down.minRttMs, loadedLatencyMs = down.rttMs, retransPct = down.retransPct,
            site = site, colo = server.name,
            error = if (avg == null) "no data received" else null,
        )
    }

    /** The Vultr single-file servers in order, skipping any used in the last minute. */
    private suspend fun vultr(progress: (SpeedProgress) -> Unit, note: (SpeedResult) -> Boolean): SpeedResult {
        var last = SpeedResult(site = "vultr", error = "not run")
        val pending = FALLBACKS.toMutableList()
        var retried = false
        while (pending.isNotEmpty()) {
            val ready = pending.firstOrNull { cooldown(it) == 0 }
            val url = ready ?: pending.minBy { cooldown(it) }
            if (ready == null) {
                while (cooldown(url) > 0) {
                    progress(SpeedProgress("All Vultr servers used in the last minute - ${Rules.siteName(url)} in ${cooldown(url)} s", 0f))
                    delay(1000)
                }
            }
            last = urlTest(url, progress)
            note(last)
            if (last.ok || last.aborted) return last
            pending.remove(url)
            if (pending.isEmpty() && last.rateLimited && !retried) {
                retried = true
                pending.add(url)
            }
        }
        return last
    }

    fun cooldown(url: String): Int {
        val lastAt = prefs.getLong("at_" + Rules.siteName(url), 0L)
        val left = COOLDOWN_S - (System.currentTimeMillis() - lastAt) / 1000
        return left.coerceAtLeast(0).toInt()
    }

    private fun markAttempt(url: String) =
        prefs.edit().putLong("at_" + Rules.siteName(url), System.currentTimeMillis()).apply()

    // ------------------------------------------------------------------ Cloudflare
    /** "HTTP 403 (cf-mitigated: challenge)", "HTTP 429 (retry-after 120 s)": Cloudflare says WHY it refused -
     *  cf-mitigated = bot protection stepped in; retry-after = plain rate limit and how long to wait. */
    private fun refusal(r: okhttp3.Response): String {
        val why = listOfNotNull(
            r.header("cf-mitigated")?.let { "cf-mitigated: $it" },
            r.header("retry-after")?.let { "retry-after $it s" },
            r.header("cf-ray")?.substringAfterLast('-', "")?.ifBlank { null }?.let { "colo $it" },
        )
        return "HTTP ${r.code}" + if (why.isEmpty()) "" else " (${why.joinToString(", ")})"
    }

    private fun ping(): Pair<Double, String> {
        val t0 = System.nanoTime()
        http.newCall(Request.Builder().url("$CLOUDFLARE/__down?bytes=0").build()).execute().use { r ->
            if (r.code == 429) throw RateLimited(refusal(r))
            if (!r.isSuccessful) throw IOException(refusal(r))
            r.body?.bytes()
            val ms = (System.nanoTime() - t0) / 1e6
            val server = Regex("dur=([\\d.]+)").find(r.header("server-timing").orEmpty())?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            val colo = r.header("cf-ray").orEmpty().substringAfterLast('-', "")
            return (ms - server).coerceAtLeast(0.1) to colo
        }
    }

    private class RateLimited(detail: String) : IOException(detail)

    private suspend fun cloudflare(progress: (SpeedProgress) -> Unit): SpeedResult = coroutineScope {
        progress(SpeedProgress("Latency", 0f))
        val pings = mutableListOf<Double>()
        var colo = ""
        try {
            repeat(8) {
                val (ms, c) = ping()
                pings += ms
                colo = c
            }
        } catch (e: RateLimited) {
            return@coroutineScope SpeedResult(site = "cloudflare", error = e.message, httpStatus = 429, rateLimited = true)
        }
        val (latency, jitter) = Rules.latencyStats(pings.drop(1)) ?: (null to null)

        // download, with latency measured alongside ("loaded latency")
        val loaded = mutableListOf<Double>()
        val start = System.nanoTime()
        val elapsed = { (System.nanoTime() - start) / 1e9 }
        val pinger = async(Dispatchers.IO) {
            // like the prototype: the first failed ping ends loaded-latency sampling (no hammering a refusing server)
            while (isActive && elapsed() < DOWN_SECONDS) {
                val ok = runCatching { loaded += ping().first }.isSuccess
                if (!ok) break
                delay(400)
            }
        }
        val samples = mutableListOf<Double>()
        var total = 0L
        var sampleBytes = 0L
        var sampleStart = start
        val buf = ByteArray(64 * 1024)
        try {
            while (elapsed() < DOWN_SECONDS) {
                http.newCall(Request.Builder().url("$CLOUDFLARE/__down?bytes=$DOWN_CHUNK").build()).execute().use { r ->
                    if (r.code == 429) throw RateLimited(refusal(r))
                    if (!r.isSuccessful) throw IOException(refusal(r))
                    val input = r.body!!.byteStream()
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        val now = System.nanoTime()
                        if (elapsed() >= 1.0) sampleBytes += n else sampleStart = now      // skip TCP ramp-up
                        if ((now - sampleStart) / 1e9 >= 1.0) {
                            samples += sampleBytes * 8 / ((now - sampleStart) / 1e9) / 1e6
                            sampleBytes = 0
                            sampleStart = now
                            progress(SpeedProgress("Download", (elapsed() / DOWN_SECONDS).toFloat().coerceAtMost(1f), samples.last()))
                        }
                        if (elapsed() >= DOWN_SECONDS) break
                    }
                }
            }
        } catch (e: RateLimited) {
            pinger.cancel()
            return@coroutineScope SpeedResult(site = "cloudflare", colo = colo, error = e.message, httpStatus = 429, rateLimited = true)
        }
        pinger.cancel()
        val downSecs = elapsed()

        // upload
        progress(SpeedProgress("Upload", 0f))
        val body = ByteArray(UP_CHUNK).toRequestBody("application/octet-stream".toMediaType())
        var upBytes = 0L
        val upStart = System.nanoTime()
        val upElapsed = { (System.nanoTime() - upStart) / 1e9 }
        runCatching {
            while (upElapsed() < UP_SECONDS) {
                coroutineContext.ensureActive()
                http.newCall(Request.Builder().url("$CLOUDFLARE/__up").post(body).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IOException(refusal(r))
                }
                upBytes += UP_CHUNK
                progress(SpeedProgress("Upload", (upElapsed() / UP_SECONDS).toFloat().coerceAtMost(1f)))
            }
        }
        val upSecs = upElapsed()

        val avg = if (samples.isNotEmpty()) samples.average() else if (downSecs > 0 && total > 0) total * 8 / downSecs / 1e6 else null
        SpeedResult(
            min = samples.minOrNull() ?: avg, avg = avg, max = samples.maxOrNull() ?: avg,
            mb = total / 1e6, secs = downSecs,
            upMbps = if (upBytes > 0 && upSecs > 0) upBytes * 8 / upSecs / 1e6 else null,
            latencyMs = latency, jitterMs = jitter,
            loadedLatencyMs = Rules.latencyStats(loaded.toList())?.first,
            site = "cloudflare", colo = colo,
            error = if (avg == null) "no data received" else null,
        )
    }

    // ------------------------------------------------------------------ single-file URL (Vultr)
    private suspend fun urlTest(url: String, progress: (SpeedProgress) -> Unit): SpeedResult {
        val site = Rules.siteName(url)
        markAttempt(url)
        progress(SpeedProgress("Download ($site)", 0f))
        val samples = mutableListOf<Double>()
        var total = 0L
        var sampleBytes = 0L
        val start = System.nanoTime()
        var sampleStart = start
        val elapsed = { (System.nanoTime() - start) / 1e9 }
        val buf = ByteArray(64 * 1024)
        return try {
            http.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).execute().use { r ->
                if (!r.isSuccessful) {
                    return SpeedResult(site = site, error = "HTTP ${r.code} ${r.message}".trim(), httpStatus = r.code,
                        rateLimited = r.code == 429 || r.code == 503)
                }
                val input = r.body!!.byteStream()
                while (elapsed() < URL_MAX_SECONDS && total < URL_MAX_BYTES) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    val now = System.nanoTime()
                    if (elapsed() >= 1.0) sampleBytes += n else sampleStart = now
                    if ((now - sampleStart) / 1e9 >= 1.0) {
                        samples += sampleBytes * 8 / ((now - sampleStart) / 1e9) / 1e6
                        sampleBytes = 0
                        sampleStart = now
                        progress(SpeedProgress("Download ($site)", (elapsed() / URL_MAX_SECONDS).toFloat(), samples.last()))
                    }
                }
            }
            val secs = elapsed()
            val avg = if (samples.isNotEmpty()) samples.average() else if (secs > 0 && total > 0) total * 8 / secs / 1e6 else null
            SpeedResult(min = samples.minOrNull() ?: avg, avg = avg, max = samples.maxOrNull() ?: avg,
                mb = total / 1e6, secs = secs, site = site, httpStatus = 200,
                error = if (avg == null) "no data received" else null)
        } catch (e: IOException) {
            SpeedResult(site = site, error = e.message?.substringBefore(" on http") ?: "connection failed")
        }
    }
}
