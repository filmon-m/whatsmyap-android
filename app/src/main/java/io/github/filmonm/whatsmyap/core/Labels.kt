package io.github.filmonm.whatsmyap.core

/**
 * AP label rules (ported from the prototype, ocr.py / parse.py).
 *
 * Labels are matched on the number after "AP" only: AP-08 = AP008 = 1001_AP8 = 8.
 */
object Labels {

    /** Common OCR slips, forgiven inside the number only. */
    private val FIX = mapOf(
        'O' to '0', 'o' to '0', 'D' to '0', 'Q' to '0',
        'I' to '1', 'l' to '1', '|' to '1', 'i' to '1',
        'S' to '5', 's' to '5', 'B' to '8', 'Z' to '2', 'z' to '2', 'G' to '6', 'g' to '9',
        // slashed zero (labels printed "AP1Ø", "APØ4") and how OCR tends to see it
        'Ø' to '0', 'ø' to '0', '∅' to '0', '⌀' to '0', 'θ' to '0', 'Θ' to '0', 'Φ' to '0', 'φ' to '0',
    )

    /** "AP" (or a usual misread: AF, AR, 4P, "A P"), an optional separator, then 1-4 digits. */
    private val LABEL = Regex(
        "(?<![A-Za-z0-9])(?:AP|AF|AR|4P|A\\s?P)[\\s_\\-.#:]{0,2}([0-9OoDQIl|iSsBZzGgØø∅⌀θΘΦφ]{1,4})(?![A-Za-z0-9Øø∅⌀θΘΦφ])",
        RegexOption.IGNORE_CASE,
    )

    data class Reading(val number: Int, val raw: String)

    /** Text read off a photo -> every AP label found in it. "AP-O8" -> (8, "AP-O8"). */
    fun parse(text: String): List<Reading> =
        LABEL.findAll(text.replace('\n', ' ')).mapNotNull { m ->
            val digits = m.groupValues[1].map { FIX[it] ?: it }.joinToString("")
            digits.toIntOrNull()?.let { Reading(it, m.value.trim()) }
        }.toList()

    /** Number after "AP" in an AP name: 1002_AP14 -> 14, AP008 -> 8; else the last digits. */
    fun apNumber(name: String?): Int? {
        if (name.isNullOrBlank()) return null
        Regex("AP[\\s_\\-]*(\\d+)", RegexOption.IGNORE_CASE).findAll(name).lastOrNull()
            ?.let { return it.groupValues[1].toInt() }
        return Regex("\\d+").findAll(name).lastOrNull()?.value?.toInt()
    }

    enum class Verdict { VERIFIED, CONFIRMED, MISMATCH, CONFLICT, CONSISTENT }

    /**
     * VERIFIED   OCR read the label and it equals the WLC's AP number (strongest)
     * CONFIRMED  no OCR reading; the typed number equals the WLC's
     * MISMATCH   OCR or the typed number differs from the WLC's
     * CONFLICT   no WLC answer; OCR and the typed number disagree
     * CONSISTENT no WLC answer; OCR and the typed number agree
     * null       nothing to judge (e.g. photo unreadable, nothing typed) -> a person reviews
     */
    fun verdict(wlc: Int?, typed: Int?, ocr: Int?): Verdict? = when {
        wlc != null && ocr != null -> if (ocr == wlc) Verdict.VERIFIED else Verdict.MISMATCH
        wlc != null && typed != null -> if (typed == wlc) Verdict.CONFIRMED else Verdict.MISMATCH
        ocr != null && typed != null -> if (ocr == typed) Verdict.CONSISTENT else Verdict.CONFLICT
        else -> null
    }
}
