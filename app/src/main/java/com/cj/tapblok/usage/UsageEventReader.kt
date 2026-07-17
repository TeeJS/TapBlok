package com.cj.tapblok.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
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
                tracker.accept(type, event.packageName, event.timeStamp)?.let(intervals::add)
            }
            if (event.timeStamp > newest) newest = event.timeStamp
        }
        if (newest > cursor) lastEventTimestamp = newest

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

    /** Forgets the cursor, so the next pump starts fresh instead of backfilling. */
    fun resetCursor() {
        prefs.edit { remove(KEY_CURSOR) }
    }

    private companion object {
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
