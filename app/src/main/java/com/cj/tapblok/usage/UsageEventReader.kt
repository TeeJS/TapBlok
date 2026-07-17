package com.cj.tapblok.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.core.content.edit

/**
 * The Android boundary: pulls raw events out of [UsageStatsManager] and feeds them to a
 * [ForegroundTracker]. Everything interesting lives in the tracker and [UsageAccountant];
 * this class only maps and pumps.
 *
 * The cursor into the event log is persisted, which is what makes process death recoverable.
 * `UsageStatsManager` retains events independently of us, so a service that was killed at
 * 22:00 and restarted at 22:05 can replay what it missed rather than silently losing five
 * minutes of usage. That turns START_STICKY restarts from a hole in the accounting into a
 * non-event.
 */
class UsageEventReader(private val context: Context) {

    /** Whether this process has established what was already on screen when it started. */
    private var seeded = false

    private val usageStatsManager
        get() = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    private val powerManager
        get() = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val prefs
        get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var lastEventTimestamp: Long
        get() = prefs.getLong(KEY_CURSOR, 0L)
        set(value) = prefs.edit { putLong(KEY_CURSOR, value) }

    /**
     * Reads every event since the last call, feeds them to [tracker] in order, and returns
     * the intervals they closed — plus a final flush at [now] so continuous use is credited
     * as it happens rather than only when the user finally puts the phone down.
     */
    fun pump(tracker: ForegroundTracker, now: Long): List<UsageInterval> {
        val intervals = mutableListOf<UsageInterval>()

        val cursor = lastEventTimestamp
        val begin = if (cursor == 0L) now - INITIAL_LOOKBACK_MS else cursor + 1
        var newest = cursor

        val events = usageStatsManager.queryEvents(begin, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            mapType(event.eventType)?.let { type ->
                tracker.accept(type, event.packageName, event.timeStamp, event.className)
                    ?.let(intervals::add)
            }
            if (event.timeStamp > newest) newest = event.timeStamp
        }
        if (newest > cursor) lastEventTimestamp = newest

        // An app that was already on screen when this process started is otherwise invisible
        // forever: its FOREGROUND event is older than the cursor, so the replay above never
        // reveals it, and no new event will fire until the user leaves and comes back. Start
        // a session with YouTube already open and it would never be blocked at all.
        //
        // Upstream's getForegroundApp() dodged this by accident — its cursor lived in a field
        // and reset to a one-hour lookback on every service start. Persisting the cursor is
        // what made the seeding necessary.
        if (!seeded) {
            seeded = true
            if (tracker.currentPackage == null) seedCurrentForeground(tracker, now)
        }

        // Belt and braces over the SCREEN_OFF event: if the screen is dark, nothing is being
        // used, whatever the event log did or didn't deliver. A missed SCREEN_NON_INTERACTIVE
        // would otherwise bill pocket time to whatever was last open.
        if (!powerManager.isInteractive) {
            tracker.accept(UsageEventType.SCREEN_OFF, null, now)?.let(intervals::add)
            return intervals
        }

        tracker.flush(now)?.let(intervals::add)
        return intervals
    }

    /**
     * Establishes which app is on screen right now, by replaying a wide window and folding it
     * down to a single answer.
     *
     * The resulting interval is opened at [now] rather than at the original FOREGROUND event's
     * timestamp. That is deliberate: the earlier time was either already accrued before this
     * process died, or was covered by the replay above. Opening at the event's real timestamp
     * would credit it a second time — a restart would hand the user a surprise bill for hours
     * they had already paid.
     */
    private fun seedCurrentForeground(tracker: ForegroundTracker, now: Long) {
        val events = usageStatsManager.queryEvents(now - INITIAL_LOOKBACK_MS, now)
        val event = UsageEvents.Event()
        var current: String? = null
        var currentClass: String? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (mapType(event.eventType)) {
                UsageEventType.FOREGROUND -> {
                    if (event.packageName == current) {
                        // Intra-app transition — same rule as the tracker: the new activity
                        // becomes the one whose closers count
                        currentClass = event.className ?: currentClass
                    } else {
                        current = event.packageName
                        currentClass = event.className
                    }
                }
                UsageEventType.BACKGROUND ->
                    if (event.packageName == current &&
                        (event.className == null || currentClass == null || event.className == currentClass)
                    ) {
                        current = null
                        currentClass = null
                    }
                UsageEventType.SCREEN_OFF -> {
                    current = null
                    currentClass = null
                }
                null -> Unit
            }
        }

        current?.let {
            Log.d(TAG, "Seeded already-open app on start: $it")
            tracker.accept(UsageEventType.FOREGROUND, it, now, currentClass)
        }
    }

    /** Forgets the cursor, so the next pump starts fresh instead of backfilling. */
    fun resetCursor() {
        prefs.edit { remove(KEY_CURSOR) }
    }

    private companion object {
        const val TAG = "UsageEventReader"
        const val PREFS = "usage_tracking"
        const val KEY_CURSOR = "last_event_timestamp"

        /**
         * Only used on a truly first run, when there is no cursor to resume from. Long
         * enough to notice an app that was already open before we started, short enough not
         * to import a whole day of history as if it were this session.
         */
        const val INITIAL_LOOKBACK_MS = 60 * 60 * 1000L

        /**
         * MOVE_TO_FOREGROUND/BACKGROUND are the pre-API-29 names for ACTIVITY_RESUMED/PAUSED
         * and share their values, so the deprecated constants cover every supported API.
         * ACTIVITY_STOPPED has no old alias and only exists from 29.
         */
        @Suppress("DEPRECATION")
        fun mapType(androidEventType: Int): UsageEventType? = when (androidEventType) {
            UsageEvents.Event.MOVE_TO_FOREGROUND -> UsageEventType.FOREGROUND
            UsageEvents.Event.MOVE_TO_BACKGROUND -> UsageEventType.BACKGROUND
            UsageEvents.Event.ACTIVITY_STOPPED -> UsageEventType.BACKGROUND
            UsageEvents.Event.SCREEN_NON_INTERACTIVE -> UsageEventType.SCREEN_OFF
            UsageEvents.Event.KEYGUARD_SHOWN -> UsageEventType.SCREEN_OFF
            else -> null
        }
    }
}
