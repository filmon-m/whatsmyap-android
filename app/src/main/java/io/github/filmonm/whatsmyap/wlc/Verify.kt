package io.github.filmonm.whatsmyap.wlc

import io.github.filmonm.whatsmyap.core.Labels

/** One AP (as the survey saw it) checked against the WLC. */
data class VerifyItem(
    val bssid: String,
    val ssid: String,
    val typed: Int?,
    val ocr: Int?,
    val ocrLabel: String?,
    val photo: String?,
    val firstSeen: String,
    val checks: Int,
    val place: String?,
    val hit: BssHit?,
    val verdict: String,
) {
    val wlcNumber get() = Parse.apNumber(hit?.ap)
}

/**
 * Verify a survey: every BSSID the survey recorded is looked up on the WLC, and the AP number the WLC
 * gives is compared with the label (OCR'd photo first, then the typed number).
 *
 * VERIFIED   the photo's label (OCR) = the WLC's AP number
 * CONFIRMED  no OCR reading; the typed number = the WLC's
 * MISMATCH   OCR or typed number differs from the WLC's - relabel or recheck
 * NO LABEL   the WLC knows the AP but the survey has no label for it
 * NOT ON WLC this BSSID isn't on this controller (another WLC, or a neighbour's network)
 */
object Verify {

    val ORDER = listOf("MISMATCH", "NOT ON WLC", "NO LABEL", "CONFIRMED", "VERIFIED")

    private fun int(s: String?) = s?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.toInt()

    fun run(rows: List<Map<String, String>>, table: BssTable): List<VerifyItem> {
        data class Key(val where: String, val typed: Int?, val ocr: Int?)
        val groups = LinkedHashMap<Key, MutableList<Map<String, String>>>()
        val hits = HashMap<String, BssHit?>()
        for (r in rows) {
            val bssid = Parse.colonMac(r["bssid"]).takeIf { it.length == 17 } ?: continue
            val hit = hits.getOrPut(bssid) { table.find(bssid) }
            val key = Key(hit?.ap ?: bssid, int(r["label_entered"]), int(r["ocr_number"]))
            groups.getOrPut(key) { mutableListOf() } += r
        }
        // a later row with a label supersedes an earlier unlabelled one at the same AP
        val labelled = groups.keys.filter { it.typed != null || it.ocr != null }.map { it.where }.toSet()
        return groups.filter { (k, _) -> k.typed != null || k.ocr != null || k.where !in labelled }.map { (k, rs) ->
            val first = rs.first()
            val bssid = Parse.colonMac(first["bssid"])
            val hit = hits[bssid]
            val photoRow = rs.lastOrNull { !it["photo_file"].isNullOrBlank() }
            val verdict = when {
                hit == null -> "NOT ON WLC"
                else -> Labels.verdict(Parse.apNumber(hit.ap), k.typed, k.ocr)?.name ?: "NO LABEL"
            }
            VerifyItem(
                bssid = bssid, ssid = first["ssid"].orEmpty(), typed = k.typed, ocr = k.ocr,
                ocrLabel = photoRow?.get("ocr_label")?.ifBlank { null }, photo = photoRow?.get("photo_file"),
                firstSeen = first["timestamp"].orEmpty(), checks = rs.size,
                place = listOf("location_name", "location_address", "location_city").firstNotNullOfOrNull { first[it]?.ifBlank { null } },
                hit = hit, verdict = verdict,
            )
        }.sortedWith(compareBy({ ORDER.indexOf(it.verdict) }, { it.wlcNumber ?: it.typed ?: it.ocr ?: Int.MAX_VALUE }))
    }

    /** One log row per item (module "verify"). */
    fun logRows(survey: String, items: List<VerifyItem>, wlcName: String, vendor: String): List<Map<String, Any?>> {
        val (site, siteType) = Health.parseWlcName(wlcName)
        return items.map { v ->
            linkedMapOf(
                "site" to site, "site_type" to siteType, "wlc_name" to wlcName, "wlc_vendor" to vendor,
                "ap_name" to v.hit?.ap, "ap_number" to v.wlcNumber, "ssid" to v.ssid, "network" to v.ssid,
                "band" to v.hit?.band?.ifEmpty { null }, "bssid" to v.bssid,
                "label_entered" to v.typed, "ocr_number" to v.ocr, "ocr_label" to v.ocrLabel, "photo_file" to v.photo,
                "label_verdict" to v.verdict.takeIf { it in setOf("VERIFIED", "CONFIRMED", "MISMATCH") },
                "verify_result" to v.verdict, "verify_match" to v.hit?.how, "survey" to survey,
                "survey_timestamp" to v.firstSeen, "survey_checks" to v.checks, "ap_location" to v.hit?.location?.ifEmpty { null },
            )
        }
    }
}
