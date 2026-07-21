package com.cj.tapblok

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Where the user is, as far as strict mode is concerned. */
enum class Presence { HOME, AWAY }

/**
 * Answers "am I at home?" on demand, so strict mode can apply near the tag and relax away
 * from it.
 *
 * **No geofence, and no background location.** Strict mode is only ever evaluated at two
 * moments — when the block screen appears and when a tag is scanned — and TapBlokPlus is in the
 * foreground for both. So this asks once, at that instant, with plain `ACCESS_FINE_LOCATION`.
 * That removes the whole apparatus a geofence would need: no registration, no re-registration
 * after reboot, no OEM battery-optimiser roulette, and no `ACCESS_BACKGROUND_LOCATION`, which
 * on Android 10+ can't even be requested inline.
 *
 * It also uses AOSP [LocationManager] rather than Play Services, keeping the app free of GMS.
 *
 * **Fix acceptance.** A cached fix is often cell-tower-derived and honestly reports ±500–2000m.
 * Testing "within 150m" against a ±1000m fix is testing noise, and with unknown-means-away that
 * noise resolves silently to *strict mode off*. So a fix is rejected unless it is both recent
 * and more accurate than the radius being tested. Only then is a fresh fix requested.
 *
 * **Unknown means away**, by explicit decision: never be stranded away from the tag. The cost
 * is that disabling location switches strict mode off — a one-toggle bypass of the feature
 * whose whole value is being un-bypassable. Accepted as a safety-first trade, with the
 * emergency override as the backstop.
 */
object HomeGeofence {

    private const val TAG = "HomeGeofence"
    private const val MAX_FIX_AGE_MS = 5 * 60 * 1000L
    private const val FRESH_FIX_TIMEOUT_MS = 10_000L

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** True once a home location has been captured. */
    fun isConfigured(context: Context): Boolean =
        AppSettings.homeLocation(context) != null

    /**
     * Whether strict mode should apply right now.
     *
     * Returns AWAY — the permissive answer — whenever the question can't be answered: no home
     * set, no permission, location off, or no fix good enough to trust.
     */
    suspend fun presence(context: Context): Presence {
        val home = AppSettings.homeLocation(context) ?: return Presence.AWAY
        if (!hasPermission(context)) return Presence.AWAY

        val radius = AppSettings.homeRadiusMetres(context)
        val fix = bestFix(context, radius) ?: run {
            Log.d(TAG, "No usable fix — treating as away.")
            return Presence.AWAY
        }

        val distance = FloatArray(1)
        Location.distanceBetween(fix.latitude, fix.longitude, home.first, home.second, distance)
        val presence = if (distance[0] <= radius) Presence.HOME else Presence.AWAY
        Log.d(TAG, "${distance[0].toInt()}m from home (radius ${radius}m, fix ±${fix.accuracy.toInt()}m) -> $presence")
        return presence
    }

    /**
     * Captures the user's current position as home, or null if no fix good enough turns up.
     *
     * Held to the same accuracy bar as the check itself: recording a ±1000m fix as "home"
     * would poison every later comparison, and the user would have no way to tell.
     */
    suspend fun captureHere(context: Context): Pair<Double, Double>? {
        if (!hasPermission(context)) return null
        val fix = bestFix(context, AppSettings.homeRadiusMetres(context)) ?: return null
        return fix.latitude to fix.longitude
    }

    /**
     * A cached fix if it's recent and accurate enough to answer the question, otherwise one
     * fresh fix with a short timeout. TapBlokPlus is on screen at this point, so a brief wait is
     * acceptable; being wrong is not.
     */
    private suspend fun bestFix(context: Context, radiusMetres: Int): Location? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val now = System.currentTimeMillis()

        val cached = providers(manager)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { now - it.time <= MAX_FIX_AGE_MS }
            .filter { it.hasAccuracy() && it.accuracy <= radiusMetres }
            .minByOrNull { it.accuracy }

        if (cached != null) return cached

        return withTimeoutOrNull(FRESH_FIX_TIMEOUT_MS) { requestFresh(manager, context) }
            ?.takeIf { it.hasAccuracy() && it.accuracy <= radiusMetres }
    }

    private fun providers(manager: LocationManager): List<String> =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

    @Suppress("MissingPermission") // guarded by hasPermission() before any call reaches here
    private suspend fun requestFresh(manager: LocationManager, context: Context): Location? =
        suspendCancellableCoroutine { cont ->
            val enabled = providers(manager)
            if (enabled.isEmpty()) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: Location) {
                    manager.removeUpdates(this)
                    if (cont.isActive) cont.resume(location)
                }

                // Required on API < 30; without them some OEM builds throw AbstractMethodError
                @Deprecated("Required for API < 30")
                override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) = Unit
                override fun onProviderEnabled(p: String) = Unit
                override fun onProviderDisabled(p: String) = Unit
            }
            try {
                // Ask every enabled provider at once and take whoever answers first. Asking
                // only GPS burned the whole timeout indoors, where it rarely gets a fix at
                // all, while the network provider (Wi-Fi) would have answered in a second or
                // two at 20–50m — comfortably inside the radius gate.
                enabled.forEach { manager.requestLocationUpdates(it, 0L, 0f, listener, Looper.getMainLooper()) }
                cont.invokeOnCancellation { manager.removeUpdates(listener) }
            } catch (e: SecurityException) {
                manager.removeUpdates(listener)
                cont.resume(null)
            }
        }
}
