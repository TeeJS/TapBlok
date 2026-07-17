package com.cj.tapblok

import android.content.Context
import android.util.Log

/**
 * The single answer to "does strict mode apply right now?".
 *
 * Every caller goes through here so the geofence can't end up applied in one place and
 * forgotten in another — strict mode being half-on is worse than it being off, because the
 * user can't predict it.
 *
 * When the geofence is off this is just the setting. When it's on, strict mode applies only
 * near home. That sounds backwards for a discipline feature until you notice the tag lives at
 * home: strict mode means "the tag is the only way out", so being away from home with strict
 * mode on means being locked out with no way to comply. The geofence is a safety mechanism,
 * not a discipline one.
 */
suspend fun strictModeApplies(context: Context): Boolean {
    val enabled = AppSettings.prefs(context).getBoolean(AppSettings.KEY_STRICT_MODE, false)
    if (!enabled) return false
    if (!AppSettings.geofenceEnabled(context)) return true

    // Never strand the user: if home was never captured, the gate can't be evaluated, so it
    // must not be allowed to trap them.
    if (!HomeGeofence.isConfigured(context)) {
        Log.d("StrictMode", "Geofence on but no home set — strict mode not applied.")
        return false
    }

    return when (HomeGeofence.presence(context)) {
        Presence.HOME -> true
        Presence.AWAY -> false
    }
}
