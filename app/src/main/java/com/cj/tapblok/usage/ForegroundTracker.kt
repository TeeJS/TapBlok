package com.cj.tapblok.usage

/** A closed span of time during which one package was actually in the foreground. */
data class UsageInterval(
    val packageName: String,
    val startMs: Long,
    val endMs: Long
) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * The subset of `UsageEvents.Event` types this tracker cares about, mapped at the Android
 * boundary so the folding logic below stays pure and unit-testable.
 */
enum class UsageEventType {
    /** ACTIVITY_RESUMED / MOVE_TO_FOREGROUND. */
    FOREGROUND,

    /** ACTIVITY_PAUSED / MOVE_TO_BACKGROUND / ACTIVITY_STOPPED. */
    BACKGROUND,

    /** SCREEN_NON_INTERACTIVE / KEYGUARD_SHOWN. */
    SCREEN_OFF
}

/**
 * Folds a stream of usage events into intervals of real foreground use.
 *
 * This exists because [AppMonitoringService]'s original `getForegroundApp()` cannot be used
 * for accounting. It consumes only MOVE_TO_FOREGROUND and never clears its
 * `currentForegroundApp`, so the value is *sticky*: lock the phone while YouTube is open and
 * it still reports YouTube for as long as the phone sits in a pocket. Sampling that once a
 * second to accrue time would burn a 25-minute budget with the screen off.
 *
 * So usage is derived from event *pairs* — an interval opens on FOREGROUND and closes on
 * BACKGROUND or SCREEN_OFF — rather than by sampling a variable.
 *
 * Not thread-safe; the monitor loop drives it from a single coroutine.
 */
class ForegroundTracker {

    private var openPackage: String? = null
    private var openClass: String? = null
    private var openSince: Long = 0

    /** The package currently accruing time, or null when nothing is. */
    val currentPackage: String? get() = openPackage

    /**
     * Feeds one event in timestamp order.
     *
     * [className] matters because of how intra-app activity transitions arrive: moving from
     * one Activity to another *within* the same app emits RESUMED for the new activity and
     * PAUSED/STOPPED for the old one — with the old activity's STOPPED often trailing by
     * seconds. Matching those closers by package alone treated the old activity's STOPPED as
     * "the app left the foreground", silently ending the interval while the app was still on
     * screen; with no further FOREGROUND event ever coming, the tracker stayed blind (no
     * accrual, no blocking) until the user switched apps. Found on device: AccuWeather's
     * onboarding flow killed tracking 0.6s in. So a BACKGROUND event only closes the interval
     * if it names the activity that is actually open.
     *
     * A null [className] (no class information) falls back to package-level matching.
     *
     * @return the interval this event closed, or null if it closed nothing. Zero-length
     * intervals are dropped rather than returned, so callers never see no-op spans.
     */
    fun accept(
        type: UsageEventType,
        packageName: String?,
        timestampMs: Long,
        className: String? = null
    ): UsageInterval? =
        when (type) {
            UsageEventType.FOREGROUND -> {
                if (packageName == null) {
                    null
                } else if (packageName == openPackage) {
                    // Intra-app activity transition: keep the interval running, but the newly
                    // resumed activity is now the one whose BACKGROUND events count — without
                    // this update, the old activity's trailing STOPPED would still match.
                    openClass = className ?: openClass
                    null
                } else {
                    val closed = close(timestampMs)
                    openPackage = packageName
                    openClass = className
                    openSince = timestampMs
                    closed
                }
            }

            // Only the activity that is actually open can close its own interval. A closer
            // for some other package — or for a sibling activity this app already navigated
            // away from — says nothing about what the user is looking at.
            UsageEventType.BACKGROUND ->
                if (packageName != null && packageName == openPackage &&
                    (className == null || openClass == null || className == openClass)
                ) {
                    close(timestampMs)
                } else {
                    null
                }

            // The screen going dark ends foreground use no matter what was open. This is the
            // case the sticky-variable approach got wrong.
            UsageEventType.SCREEN_OFF -> close(timestampMs)
        }

    /**
     * Closes the open interval at [now] and immediately reopens it for the same package.
     *
     * Continuous use generates no events at all — sit in YouTube for 25 minutes and nothing
     * fires between the first RESUMED and the eventual PAUSED. Without periodic flushing,
     * usage would only ever be credited *after* the user stopped, and a session limit could
     * never trigger while they were still scrolling. The monitor loop calls this each tick.
     *
     * @return the interval covering the time since the last flush, or null if nothing is open
     * or no time has passed.
     */
    fun flush(now: Long): UsageInterval? {
        val pkg = openPackage ?: return null
        val cls = openClass
        val closed = close(now)
        openPackage = pkg
        openClass = cls
        openSince = now
        return closed
    }

    /**
     * Drops the open interval without crediting it — for time that elapsed but must not be
     * charged to anyone, e.g. while the block screen is up.
     */
    fun discardOpen(now: Long) {
        if (openPackage != null) {
            openPackage = null
            openClass = null
            openSince = now
        }
    }

    private fun close(at: Long): UsageInterval? {
        val pkg = openPackage ?: return null
        val start = openSince
        openPackage = null
        openClass = null
        openSince = 0
        // Guard against a clock that moved backwards as well as against zero-length spans
        return if (at > start) UsageInterval(pkg, start, at) else null
    }
}
