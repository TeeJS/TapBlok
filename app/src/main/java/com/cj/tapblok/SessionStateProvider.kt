package com.cj.tapblok

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Lets companion apps ask "is a TapBlokPlus session active right now?" — built for
 * ShortBlock, which enforces its Shorts/Reels blocking only while TapBlokPlus is enforcing
 * app limits, so one tag tap governs both apps.
 *
 * Read access requires [READ_PERMISSION], a signature-level permission: only apps signed
 * with the same key as TapBlokPlus can hold it, so this surface is invisible to everything else
 * on the device. The manifest's readPermission guards the query path, but
 * [ContentProvider.call] does NOT go through that enforcement — a documented Android gap —
 * so [call] checks the caller's permission itself. Both checks matter; neither alone covers
 * both entry points.
 *
 * State pushes go out as [AppMonitoringService.ACTION_SESSION_STARTED]/STOPPED broadcasts
 * under the same permission. Note for companions: those are implicit broadcasts, which
 * Android 8+ won't deliver to another app's manifest-declared receiver — register a runtime
 * receiver from a long-lived component (an accessibility service qualifies) and use this
 * provider for the initial state on startup.
 */
class SessionStateProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.tj.tapblok.state"
        const val METHOD_STATE = "state"
        const val KEY_SESSION_ACTIVE = "session_active"
        const val KEY_STRICT_MODE = "strict_mode"
        const val READ_PERMISSION = "com.tj.tapblok.permission.READ_SESSION_STATE"
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = context ?: return null
        if (context.checkCallingPermission(READ_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Caller lacks $READ_PERMISSION")
        }
        if (method != METHOD_STATE) return null

        // monitoring_active rather than the in-process isRunning flag: it survives process
        // death and reflects intent — true across a task-swipe restart gap means "a session
        // is in force and will resume", which is the answer a companion enforcing alongside
        // us actually needs.
        val prefs = AppSettings.prefs(context)
        return Bundle().apply {
            putBoolean(KEY_SESSION_ACTIVE, prefs.getBoolean("monitoring_active", false))
            putBoolean(KEY_STRICT_MODE, prefs.getBoolean(AppSettings.KEY_STRICT_MODE, false))
        }
    }

    // This provider speaks only through call(); the table-style API is intentionally inert.
    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
