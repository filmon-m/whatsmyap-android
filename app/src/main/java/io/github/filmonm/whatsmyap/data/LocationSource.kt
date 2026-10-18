package io.github.filmonm.whatsmyap.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Where the tablet is (replaces termux-location + Nominatim). */
data class Place(
    val lat: Double,
    val lon: Double,
    val accuracyM: Int?,
    val source: String,
    val name: String = "",
    val address: String = "",
    val city: String = "",
) {
    fun text(): String {
        val parts = listOf(name, address, city).filter { it.isNotBlank() }
        val base = parts.ifEmpty { listOf("%.4f, %.4f".format(lat, lon)) }.joinToString(" · ")
        return base + (accuracyM?.let { "  (±$it m)" } ?: "")
    }
}

object LocationSource {

    private val STATES = mapOf(
        "Alabama" to "AL", "Alaska" to "AK", "Arizona" to "AZ", "Arkansas" to "AR", "California" to "CA",
        "Colorado" to "CO", "Connecticut" to "CT", "Delaware" to "DE", "District of Columbia" to "DC",
        "Florida" to "FL", "Georgia" to "GA", "Hawaii" to "HI", "Idaho" to "ID", "Illinois" to "IL",
        "Indiana" to "IN", "Iowa" to "IA", "Kansas" to "KS", "Kentucky" to "KY", "Louisiana" to "LA",
        "Maine" to "ME", "Maryland" to "MD", "Massachusetts" to "MA", "Michigan" to "MI", "Minnesota" to "MN",
        "Mississippi" to "MS", "Missouri" to "MO", "Montana" to "MT", "Nebraska" to "NE", "Nevada" to "NV",
        "New Hampshire" to "NH", "New Jersey" to "NJ", "New Mexico" to "NM", "New York" to "NY",
        "North Carolina" to "NC", "North Dakota" to "ND", "Ohio" to "OH", "Oklahoma" to "OK", "Oregon" to "OR",
        "Pennsylvania" to "PA", "Rhode Island" to "RI", "South Carolina" to "SC", "South Dakota" to "SD",
        "Tennessee" to "TN", "Texas" to "TX", "Utah" to "UT", "Vermont" to "VT", "Virginia" to "VA",
        "Washington" to "WA", "West Virginia" to "WV", "Wisconsin" to "WI", "Wyoming" to "WY",
    )

    /** Current position (network provider: fast, works indoors), falling back to the last known one. */
    @SuppressLint("MissingPermission")
    suspend fun fix(context: Context, timeoutMs: Long = 12_000): Location? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val provider = when {
            Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> LocationManager.GPS_PROVIDER
        }
        val fresh = try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val cancel = CancellationSignal()
                    cont.invokeOnCancellation { cancel.cancel() }
                    lm.getCurrentLocation(provider, cancel, context.mainExecutor) { loc -> cont.resume(loc) }
                }
            }
        } catch (e: SecurityException) {
            return null
        } catch (e: IllegalArgumentException) {
            null
        }
        if (fresh != null) return fresh
        return try {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
        } catch (e: SecurityException) {
            null
        }
    }

    /** Street address and city from Android's built-in geocoder; blank if it can't. */
    suspend fun describe(context: Context, loc: Location): Place {
        val base = Place(loc.latitude, loc.longitude, loc.accuracy.toInt().takeIf { loc.hasAccuracy() }, loc.provider ?: "")
        if (!Geocoder.isPresent()) return base
        val addr: Address? = withContext(Dispatchers.IO) {
            val g = Geocoder(context)
            if (Build.VERSION.SDK_INT >= 33) {
                withTimeoutOrNull(8_000) {
                    suspendCancellableCoroutine<Address?> { cont ->
                        g.getFromLocation(loc.latitude, loc.longitude, 1, object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) { cont.resume(addresses.firstOrNull()) }
                            override fun onError(errorMessage: String?) { cont.resume(null) }
                        })
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                runCatching { g.getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull() }.getOrNull()
            }
        }
        addr ?: return base
        val street = listOfNotNull(addr.subThoroughfare, addr.thoroughfare).joinToString(" ")
        val town = addr.locality ?: addr.subLocality ?: addr.subAdminArea ?: ""
        val st = STATES[addr.adminArea] ?: addr.adminArea ?: ""
        val name = addr.featureName?.takeIf { it != addr.subThoroughfare && it != street && it.any(Char::isLetter) } ?: ""
        return base.copy(
            name = name, address = street,
            city = if (town.isNotBlank()) listOf(town, st).filter { it.isNotBlank() }.joinToString(", ") else "",
        )
    }
}
