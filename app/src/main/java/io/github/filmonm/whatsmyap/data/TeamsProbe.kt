package io.github.filmonm.whatsmyap.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom
import kotlin.math.abs

/**
 * Teams readiness the way Microsoft's Network Assessment Tool measures it: UDP to a Teams media
 * relay on port 3478 - the path real calls take. Small STUN Binding requests are sent at audio
 * pace (every 20 ms) for a few seconds; each answer gives a round trip, missing answers are loss.
 */
object TeamsProbe {

    data class Result(
        val relay: String,
        val sent: Int,
        val received: Int,
        val rttMs: Double?,         // median round trip
        val jitterMs: Double?,      // mean change between consecutive round trips (RFC 3550 style)
        val lossPct: Double?,
        val error: String? = null,
    ) {
        val ok get() = received > 0
    }

    private const val MAGIC = 0x2112A442
    private const val PORT = 3478
    private const val INTERVAL_MS = 20L

    /** A STUN Binding Request (RFC 5389): 20-byte header, no attributes. */
    fun bindingRequest(txId: ByteArray): ByteArray {
        require(txId.size == 12)
        val b = ByteArray(20)
        b[0] = 0x00; b[1] = 0x01                   // Binding Request
        b[2] = 0x00; b[3] = 0x00                   // no attributes
        b[4] = (MAGIC ushr 24).toByte(); b[5] = (MAGIC ushr 16).toByte()
        b[6] = (MAGIC ushr 8).toByte(); b[7] = MAGIC.toByte()
        txId.copyInto(b, 8)
        return b
    }

    /** The transaction ID of a STUN response (any class: success or error both prove the round trip). */
    fun responseTxId(data: ByteArray, len: Int): String? {
        if (len < 20) return null
        val type = ((data[0].toInt() and 0xff) shl 8) or (data[1].toInt() and 0xff)
        if (type and 0x0110 == 0) return null      // not a success (0x0101) or error (0x0111) response
        val cookie = ((data[4].toInt() and 0xff) shl 24) or ((data[5].toInt() and 0xff) shl 16) or
            ((data[6].toInt() and 0xff) shl 8) or (data[7].toInt() and 0xff)
        if (cookie != MAGIC) return null
        return data.copyOfRange(8, 20).joinToString("") { "%02x".format(it) }
    }

    /** Median round trip and jitter from round trips in send order (null = no answer). */
    fun stats(rtts: List<Double?>): Pair<Double?, Double?> {
        val got = rtts.filterNotNull()
        if (got.isEmpty()) return null to null
        val s = got.sorted()
        val median = if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        val jitter = if (got.size < 2) 0.0 else got.zipWithNext { a, b -> abs(a - b) }.average()
        return median to jitter
    }

    suspend fun run(relayHost: String, seconds: Int, progress: (Float) -> Unit): Result = withContext(Dispatchers.IO) {
        val addr: InetAddress = try {
            InetAddress.getAllByName(relayHost).let { all -> all.firstOrNull { it is Inet4Address } ?: all.first() }
        } catch (e: Exception) {
            return@withContext Result(relayHost, 0, 0, null, null, null, "can't resolve $relayHost")
        }
        val count = (seconds * 1000 / INTERVAL_MS).toInt()
        val sentAt = HashMap<String, Long>(count * 2)
        val order = ArrayList<String>(count)
        val rtt = HashMap<String, Double>(count * 2)
        val rnd = SecureRandom()
        DatagramSocket().use { sock ->
            sock.soTimeout = 200
            sock.connect(InetSocketAddress(addr, PORT))
            coroutineScope {
                val receiver = async(Dispatchers.IO) {
                    val buf = ByteArray(1500)
                    val pkt = DatagramPacket(buf, buf.size)
                    while (isActive) {
                        try {
                            sock.receive(pkt)
                            val now = System.nanoTime()
                            val id = responseTxId(buf, pkt.length) ?: continue
                            val t0 = synchronized(sentAt) { sentAt[id] } ?: continue
                            synchronized(rtt) { rtt.putIfAbsent(id, (now - t0) / 1e6) }
                        } catch (e: SocketTimeoutException) {
                            // keep listening until cancelled
                        } catch (e: Exception) {
                            break
                        }
                    }
                }
                val start = System.nanoTime()
                for (i in 0 until count) {
                    val tx = ByteArray(12).also { rnd.nextBytes(it) }
                    val id = tx.joinToString("") { "%02x".format(it) }
                    synchronized(sentAt) { sentAt[id] = System.nanoTime() }
                    order += id
                    runCatching { sock.send(DatagramPacket(bindingRequest(tx), 20)) }
                    if (i % 25 == 0) progress(i.toFloat() / count)
                    // keep an even 20 ms pace regardless of how long the send took
                    val next = start + (i + 1) * INTERVAL_MS * 1_000_000
                    val wait = (next - System.nanoTime()) / 1_000_000
                    if (wait > 0) delay(wait)
                }
                delay(1000)                                  // late answers still count, not as loss
                receiver.cancel()
            }
        }
        val rtts = order.map { synchronized(rtt) { rtt[it] } }
        val received = rtts.count { it != null }
        val (median, jitter) = stats(rtts)
        Result(
            relay = "$relayHost (${addr.hostAddress})",
            sent = count, received = received, rttMs = median, jitterMs = jitter,
            lossPct = if (received == 0) null else 100.0 * (count - received) / count,
            error = if (received == 0) "no answer from the Teams relay on UDP $PORT (blocked, or the relay ignores these probes)" else null,
        )
    }
}
