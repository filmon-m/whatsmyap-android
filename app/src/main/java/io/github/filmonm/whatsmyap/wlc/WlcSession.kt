package io.github.filmonm.whatsmyap.wlc

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.io.InputStream
import java.io.OutputStream

/** Remembers each WLC's SSH host key: the first visit trusts it, a later change is refused. */
interface HostKeys {
    fun get(host: String): String?
    fun put(host: String, fingerprint: String)
}

class LoginRejected(msg: String) : Exception(msg)
class HostKeyChanged(val host: String, val fingerprint: String) :
    Exception("This WLC's SSH key changed since the last visit. If the controller was replaced or upgraded, trust the new key.")

/**
 * SSH session to a WLC: log in once, detect the vendor, run show commands (port of the prototype's base.Session,
 * which used netmiko).
 *
 * Lockout guard: the password is offered to the WLC at most ONCE per session, and after a rejected login
 * this session never tries again.
 */
class WlcSession(
    private val host: String,
    private val user: String,
    private val pw: String,
    private val hostKeys: HostKeys,
    private val port: Int = 22,
) : Runner {

    var vendor: String? = null          // "cisco9800" | "aruba8"
        private set
    override var name: String = host
        private set
    var authFailed = false
        private set
    /** Progress hook: called with each command before it runs. */
    var onCommand: ((String) -> Unit)? = null
    /** Every exchange, for the "Raw WLC output" view and troubleshooting a new firmware. */
    val transcript = StringBuilder()

    private var session: Session? = null
    private var shell: ChannelShell? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var prompt: Regex? = null

    companion object {
        const val CONNECT_MS = 20_000
        const val COMMAND_MS = 45_000L

        init {
            // WLCs often still offer older algorithms; keep the modern ones first and allow the older ones after.
            fun append(key: String, vararg extra: String) {
                val have = JSch.getConfig(key).split(',').filter { it.isNotBlank() }
                JSch.setConfig(key, (have + extra.filter { it !in have }).joinToString(","))
            }
            append("server_host_key", "ssh-rsa")
            append("kex", "diffie-hellman-group14-sha1", "diffie-hellman-group-exchange-sha1")
        }

    }

    // ----------------------------------------------------------------------------- connect
    fun connect() {
        if (authFailed) throw LoginRejected("Login was rejected earlier - not retrying (lockout guard)")
        val jsch = JSch()
        val s = jsch.getSession(user, host, port)
        var offered = false
        // one answer only, whichever method the WLC asks with (password or keyboard-interactive)
        val ui = object : UserInfo, UIKeyboardInteractive {
            override fun getPassphrase(): String? = null
            override fun getPassword(): String? = if (offered) null else pw.also { offered = true }
            override fun promptPassword(message: String?): Boolean = !offered
            override fun promptPassphrase(message: String?): Boolean = false
            override fun promptYesNo(message: String?): Boolean = false
            override fun showMessage(message: String?) {}                     // login banner
            override fun promptKeyboardInteractive(
                destination: String?, name: String?, instruction: String?, prompt: Array<String>?, echo: BooleanArray?,
            ): Array<String>? {
                if (prompt.isNullOrEmpty()) return arrayOf()
                if (offered) return null
                offered = true
                return Array(prompt.size) { pw }
            }
        }
        s.userInfo = ui
        s.setConfig("StrictHostKeyChecking", "no")                 // we pin the key ourselves, below
        s.setConfig("PreferredAuthentications", "keyboard-interactive,password")
        s.serverAliveInterval = 30_000
        try {
            s.connect(CONNECT_MS)
        } catch (e: JSchException) {
            val m = (e.message ?: "").lowercase()
            if ("auth fail" in m || "auth cancel" in m || "authentication" in m) {
                authFailed = true
                throw LoginRejected("Login rejected by WLC (username/password)")
            }
            throw e
        }
        val fp = s.hostKey?.getFingerPrint(jsch) ?: ""
        val known = hostKeys.get(host)
        if (known != null && known != fp) {
            s.disconnect()
            throw HostKeyChanged(host, fp)
        }
        if (known == null && fp.isNotEmpty()) hostKeys.put(host, fp)

        val ch = s.openChannel("shell") as ChannelShell
        ch.setPtyType("vt100", 511, 200, 0, 0)
        input = ch.inputStream
        output = ch.outputStream
        ch.connect(CONNECT_MS)
        session = s
        shell = ch

        // the login banner and MOTD come first; the prompt is the last line once the WLC goes quiet
        var first = readQuiet(12_000)
        if (!looksLikePrompt(first)) { send(""); first = readQuiet(6_000) }
        val p = lastLine(first)
        if (!looksLikePrompt(first)) throw IllegalStateException("No command prompt from the WLC (last line: ${p.take(60)})")
        if (vendor == null) vendor = detect(p)
        val v = vendor!!
        name = Cli.ARUBA_PROMPT.find(p)?.groupValues?.get(1) ?: p.trimEnd().trimEnd('#', '>', ' ').substringBefore('(')
        prompt = Cli.promptFor(v, name)
        // paging off so long tables arrive whole; wide terminal so lines don't wrap
        if (v == "aruba8") exchange("no paging") else { exchange("terminal length 0"); exchange("terminal width 511") }
    }

    private fun looksLikePrompt(text: String) = Cli.looksLikePrompt(text)

    /** Read until the WLC has said something that ends in a prompt and then stays quiet for a moment. */
    private fun readQuiet(timeoutMs: Long, quietMs: Long = 1_200): String {
        var text = ""
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val before = text
            text = before + readUntil(minOf(quietMs, end - System.currentTimeMillis()).coerceAtLeast(1)) { false }
            if (text == before && looksLikePrompt(text)) return text
        }
        return text
    }

    private fun lastLine(text: String) = Cli.lastLine(text)

    private fun detect(p: String): String {
        if ("Cisco Controller" in p) throw IllegalStateException("AireOS controller detected - not supported")
        if (Cli.ARUBA_PROMPT.matches(p)) return "aruba8"
        send("show version | include IOS XE")
        val out = readUntil(8_000) { it.count { c -> c == '\n' } >= 1 && looksLikePrompt(it) }
        val bodyText = out.lines().filter { "show version" !in it }.joinToString("\n")
        return when {
            "IOS XE" in bodyText -> "cisco9800"
            "ArubaOS" in bodyText || "Aruba" in bodyText -> "aruba8"
            else -> throw IllegalStateException("Could not identify WLC type (prompt: $p)")
        }
    }

    // ----------------------------------------------------------------------------- I/O
    private fun send(line: String) {
        val o = output ?: throw IllegalStateException("not connected")
        o.write((line + "\n").toByteArray())
        o.flush()
    }

    private fun drain() {
        val i = input ?: return
        while (i.available() > 0) i.read(ByteArray(i.available()))
    }

    /** Read until [done] says so, the WLC goes quiet after looking finished, or the time runs out. */
    private fun readUntil(timeoutMs: Long, done: (String) -> Boolean): String {
        val i = input ?: throw IllegalStateException("not connected")
        val raw = StringBuilder()
        val buf = ByteArray(16_384)
        val end = System.currentTimeMillis() + timeoutMs
        var text = ""
        while (System.currentTimeMillis() < end) {
            if (shell?.isClosed == true) throw java.io.IOException("WLC closed the session")
            val n = i.available()
            if (n > 0) {
                val r = i.read(buf, 0, minOf(n, buf.size))
                if (r > 0) {
                    raw.append(String(buf, 0, r, Charsets.UTF_8))
                    text = Cli.clean(raw.toString())
                    if (text.trimEnd().endsWith("--More--") || text.endsWith("--More-- ")) {   // paging left on: keep going
                        output?.apply { write(" ".toByteArray()); flush() }
                    }
                    if (done(text)) return text
                    continue
                }
            }
            Thread.sleep(40)
        }
        return text
    }

    private fun exchange(cmd: String): String {
        drain()
        send(cmd)
        val p = prompt!!
        val text = readUntil(COMMAND_MS) { t ->
            val lines = t.split('\n')
            lines.size > 1 && p.matches(lines.last().trimEnd())
        }
        transcript.append("> ").append(cmd).append('\n').append(text).append('\n')
        if (transcript.length > 400_000) transcript.delete(0, transcript.length - 300_000)
        return Cli.body(text.replace("--More--", ""), cmd, p)
    }

    /** Run a show command; reconnect once if the session dropped (e.g. after a roam). */
    @Synchronized
    override fun run(cmd: String): String {
        onCommand?.invoke(cmd)
        for (attempt in 1..2) {
            try {
                if (shell == null || shell?.isClosed == true || session?.isConnected != true) {
                    close()
                    connect()
                }
                return exchange(cmd)
            } catch (e: Exception) {
                close()
                if (attempt == 2 || authFailed || e is LoginRejected || e is HostKeyChanged) throw e
            }
        }
        error("unreachable")
    }

    @Synchronized
    fun close() {
        runCatching { shell?.disconnect() }
        runCatching { session?.disconnect() }
        shell = null; session = null; input = null; output = null
    }
}
