package io.github.filmonm.whatsmyap.data

import io.github.filmonm.whatsmyap.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ndt7 - the M-Lab test behind Google's "internet speed test" (spec: m-lab/ndt-server ndt7-protocol.md).
 * Internet: M-Lab's nearest server via the Locate API (results join M-Lab's public dataset).
 * Local:    an ndt-server you run yourself, e.g. on the Pi in the site's MDF (ws://<ip>:8080).
 * Download: the server streams for ~10 s; upload: we stream for ~10 s. The server also reports
 * its TCP view (MinRTT, RTT, retransmitted bytes), which gives latency under load and retransmits.
 */
class Ndt7(private val userAgent: String = "WhatsMyAP/" + BuildConfig.VERSION_NAME) {

    data class Server(val name: String, val download: String, val upload: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS)
        .build()

    /** M-Lab's nearest server (Locate API v2). */
    suspend fun locate(): Server = withContext(Dispatchers.IO) {
        val url = "https://locate.measurementlab.net/v2/nearest/ndt/ndt7" +
            "?client_name=whatsmyap-android&client_version=${BuildConfig.VERSION_NAME}"
        http.newCall(Request.Builder().url(url).header("User-Agent", userAgent).header("Accept", "application/json").build())
            .execute().use { r ->
                if (r.code == 204) throw java.io.IOException("M-Lab has no capacity right now (HTTP 204)")
                if (!r.isSuccessful) throw java.io.IOException("M-Lab locate: HTTP ${r.code}")
                val first = JSONObject(r.body!!.string()).getJSONArray("results").getJSONObject(0)
                val urls = first.getJSONObject("urls")
                Server(
                    name = first.optString("machine").substringAfter("mlab").substringAfter('-').substringBefore('.'),
                    download = urls.getString("wss:///ndt/v7/download"),
                    upload = urls.getString("wss:///ndt/v7/upload"),
                )
            }
    }

    /** A self-hosted ndt-server (no tokens), cleartext websocket. */
    fun local(hostPort: String) = Server(hostPort, "ws://$hostPort/ndt/v7/download", "ws://$hostPort/ndt/v7/upload")

    class Measured {
        val samples = mutableListOf<Double>()   // Mbps per second
        var bytes = 0L
        var secs = 0.0
        var minRttMs: Double? = null
        var rttMs: Double? = null
        var retransPct: Double? = null
        var error: String? = null
    }

    private fun request(url: String) = Request.Builder().url(url)
        .header("User-Agent", userAgent)
        .header("Sec-WebSocket-Protocol", "net.measurementlab.ndt.v7")
        .build()

    private fun readTcpInfo(text: String, m: Measured) {
        val t = runCatching { JSONObject(text).optJSONObject("TCPInfo") }.getOrNull() ?: return
        if (t.has("MinRTT")) m.minRttMs = t.optLong("MinRTT") / 1000.0
        if (t.has("RTT")) m.rttMs = t.optLong("RTT") / 1000.0
        val sent = t.optLong("BytesSent", 0)
        if (sent > 0 && t.has("BytesRetrans")) m.retransPct = 100.0 * t.optLong("BytesRetrans") / sent
    }

    /** Download: count the server's binary messages, sampled each second (first second skipped: TCP ramp-up). */
    suspend fun download(url: String, progress: (Float, Double?) -> Unit): Measured {
        val m = Measured()
        val done = CompletableDeferred<Unit>()
        var start = 0L
        var sampleStart = 0L
        var sampleBytes = 0L
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                start = System.nanoTime(); sampleStart = start
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val now = System.nanoTime()
                m.bytes += bytes.size
                if ((now - start) / 1e9 >= 1.0) sampleBytes += bytes.size else sampleStart = now
                if ((now - sampleStart) / 1e9 >= 1.0) {
                    m.samples += sampleBytes * 8 / ((now - sampleStart) / 1e9) / 1e6
                    sampleBytes = 0; sampleStart = now
                    progress(((now - start) / 1e9 / 10.0).toFloat().coerceAtMost(1f), m.samples.last())
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) = readTcpInfo(text, m)

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null); done.complete(Unit)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { done.complete(Unit) }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (m.bytes == 0L) m.error = response?.let { "HTTP ${it.code}" } ?: (t.message ?: t.javaClass.simpleName)
                done.complete(Unit)                     // an abrupt end after data is a normal finish
            }
        }
        val ws = http.newWebSocket(request(url), listener)
        if (withTimeoutOrNull(15_000) { done.await() } == null) ws.cancel()
        m.secs = if (start > 0) (System.nanoTime() - start) / 1e9 else 0.0
        return m
    }

    /** Upload: send binary messages for 10 s, keeping a modest amount queued; count what actually left. */
    suspend fun upload(url: String, progress: (Float) -> Unit): Measured {
        val m = Measured()
        val opened = CompletableDeferred<Boolean>()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(true) }
            override fun onMessage(webSocket: WebSocket, text: String) = readTcpInfo(text, m)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!opened.isCompleted) m.error = response?.let { "HTTP ${it.code}" } ?: (t.message ?: t.javaClass.simpleName)
                opened.complete(false)
            }
        }
        val ws = http.newWebSocket(request(url), listener)
        if (withTimeoutOrNull(10_000) { opened.await() } != true) { ws.cancel(); return m }
        var size = 1 shl 13
        var queued = 0L
        val start = System.nanoTime()
        val elapsed = { (System.nanoTime() - start) / 1e9 }
        while (elapsed() < 10.0) {
            while (ws.queueSize() < 4_000_000 && elapsed() < 10.0) {
                if (!ws.send(ByteArray(size).toByteString())) break
                queued += size
                if (size < (1 shl 20) && size < queued / 16) size *= 2       // grow as the spec suggests
            }
            progress((elapsed() / 10.0).toFloat())
            delay(50)
        }
        m.secs = elapsed()
        m.bytes = queued - ws.queueSize()
        ws.close(1000, null)
        return m
    }
}
