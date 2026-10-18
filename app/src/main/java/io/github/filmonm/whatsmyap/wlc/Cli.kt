package io.github.filmonm.whatsmyap.wlc

/** The WLC command line as text: prompts, echoes, paging and colour codes (no SSH here, so it unit-tests). */
object Cli {
    private val ANSI = Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]|\u001B[()][A-Za-z0-9]|\u0007""")

    /** Text as a person would see it: no colour codes, no carriage returns, backspaces applied. */
    fun clean(raw: String): String {
        val s = ANSI.replace(raw, "").replace("\r\n", "\n").replace("\r", "")
        if ('\b' !in s) return s
        val b = StringBuilder()
        for (c in s) if (c == '\b') { if (b.isNotEmpty()) b.setLength(b.length - 1) } else b.append(c)
        return b.toString()
    }

    /** Aruba: "(name) #", "(name) [mynode] #", "(name) *[mynode] #" (* = unsaved config). */
    val ARUBA_PROMPT = Regex("""^\((.+?)\)\s*\*?\s*(\[.*\])?\s*\*?\s*[#>]\s*$""")

    fun promptFor(vendor: String, name: String): Regex = if (vendor == "aruba8")
        Regex("""^\(${Regex.escape(name)}\)\s*\*?\s*(\[.*\])?\s*\*?\s*[#>]\s*$""")
    else Regex("""^${Regex.escape(name)}(\(.*\))?[#>]\s*$""")

    /** Command output without the echoed command line and the closing prompt. */
    fun body(text: String, cmd: String, prompt: Regex): String {
        val lines = text.split('\n').toMutableList()
        if (lines.isNotEmpty() && prompt.matches(lines.last().trimEnd())) lines.removeAt(lines.size - 1)
        val echo = lines.indexOfFirst { cmd.trim() in it }.takeIf { it in 0..2 }
        val start = if (echo != null) echo + 1 else 0
        val rest = lines.drop(start)
        return if (rest.isEmpty()) "" else rest.joinToString("\n") + "\n"
    }

    fun lastLine(text: String) = text.split('\n').lastOrNull { it.isNotBlank() }?.trim() ?: ""

    /** "(name) *[mynode] #" or "es-name-w1#" - not a banner line of #####. */
    fun looksLikePrompt(text: String): Boolean {
        val l = lastLine(text)
        return ARUBA_PROMPT.matches(l) || Regex("""^[A-Za-z0-9._-]+(\(.*\))?[#>]$""").matches(l)
    }
}
