package com.cj.tapblok.usage

import com.cj.tapblok.usage.UsageEventType.BACKGROUND
import com.cj.tapblok.usage.UsageEventType.FOREGROUND
import com.cj.tapblok.usage.UsageEventType.SCREEN_OFF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val MIN = 60_000L
private const val YT = "com.google.android.youtube"
private const val IG = "com.instagram.android"

class ForegroundTrackerTest {

    private val tracker = ForegroundTracker()

    @Test
    fun `foreground then background yields one interval of the right length`() {
        assertNull(tracker.accept(FOREGROUND, YT, 0))

        val interval = tracker.accept(BACKGROUND, YT, 10 * MIN)

        assertEquals(YT, interval?.packageName)
        assertEquals(10 * MIN, interval?.durationMs)
    }

    @Test
    fun `switching apps closes the first and opens the second`() {
        tracker.accept(FOREGROUND, YT, 0)

        val closed = tracker.accept(FOREGROUND, IG, 5 * MIN)

        assertEquals(YT, closed?.packageName)
        assertEquals(5 * MIN, closed?.durationMs)
        assertEquals(IG, tracker.currentPackage)
    }

    /**
     * The regression this whole class exists for. Sampling a sticky currentForegroundApp
     * would have charged the pocket time to YouTube.
     */
    @Test
    fun `screen off ends accrual - a locked phone in a pocket charges nobody`() {
        tracker.accept(FOREGROUND, YT, 0)

        val closed = tracker.accept(SCREEN_OFF, null, 2 * MIN)
        assertEquals(2 * MIN, closed?.durationMs)
        assertNull(tracker.currentPackage)

        // 30 minutes face-down in a pocket
        assertNull(tracker.flush(32 * MIN))
        assertNull(tracker.currentPackage)
    }

    @Test
    fun `use resumes normally after the screen comes back`() {
        tracker.accept(FOREGROUND, YT, 0)
        tracker.accept(SCREEN_OFF, null, 2 * MIN)

        tracker.accept(FOREGROUND, YT, 40 * MIN)
        val second = tracker.accept(BACKGROUND, YT, 43 * MIN)

        // only the 2 min before and 3 min after, never the 38 min asleep
        assertEquals(3 * MIN, second?.durationMs)
    }

    /**
     * Continuous use emits no events at all, so without flushing a session limit could never
     * fire while the user was still scrolling.
     */
    @Test
    fun `flush credits continuous use before the app is ever backgrounded`() {
        tracker.accept(FOREGROUND, YT, 0)

        val first = tracker.flush(MIN)
        val second = tracker.flush(2 * MIN)

        assertEquals(MIN, first?.durationMs)
        assertEquals(MIN, second?.durationMs)
        assertEquals(YT, tracker.currentPackage)
    }

    @Test
    fun `flushes sum to the true elapsed time with no double counting`() {
        tracker.accept(FOREGROUND, YT, 0)

        val total = (1..25).sumOf { tracker.flush(it * MIN)?.durationMs ?: 0 }

        assertEquals(25 * MIN, total)
    }

    @Test
    fun `an intra-app activity transition does not fragment the interval`() {
        tracker.accept(FOREGROUND, YT, 0)

        // same package resumed again - a new Activity within YouTube
        assertNull(tracker.accept(FOREGROUND, YT, 3 * MIN))

        val closed = tracker.accept(BACKGROUND, YT, 10 * MIN)
        assertEquals(10 * MIN, closed?.durationMs)
    }

    @Test
    fun `a background event for some other app is ignored`() {
        tracker.accept(FOREGROUND, YT, 0)

        assertNull(tracker.accept(BACKGROUND, IG, 5 * MIN))
        assertEquals(YT, tracker.currentPackage)

        assertEquals(10 * MIN, tracker.accept(BACKGROUND, YT, 10 * MIN)?.durationMs)
    }

    @Test
    fun `nothing accrues when nothing is open`() {
        assertNull(tracker.flush(MIN))
        assertNull(tracker.accept(BACKGROUND, YT, MIN))
        assertNull(tracker.accept(SCREEN_OFF, null, MIN))
    }

    @Test
    fun `discardOpen drops time instead of crediting it`() {
        tracker.accept(FOREGROUND, YT, 0)

        tracker.discardOpen(5 * MIN)

        assertNull(tracker.currentPackage)
        assertNull(tracker.flush(10 * MIN))
    }

    /**
     * The regression AccuWeather found on device. Moving between activities *within* an app
     * emits RESUMED for the new activity and a trailing STOPPED for the old one — often
     * seconds later. Matched by package alone, that trailing STOPPED closed the interval
     * while the app was still on screen, and with no further FOREGROUND event coming the
     * tracker stayed blind (no accrual, no blocking) until the user switched apps.
     */
    @Test
    fun `a trailing STOPPED from a previous activity does not end tracking`() {
        tracker.accept(FOREGROUND, YT, 0, "MainActivity")
        // intra-app navigation: new activity resumes first...
        assertNull(tracker.accept(FOREGROUND, YT, 10_000, "PlayerActivity"))
        // ...then the old one's STOPPED trails in. Same package — but not the open activity.
        assertNull(tracker.accept(BACKGROUND, YT, 12_000, "MainActivity"))

        // still tracking: time keeps accruing
        assertEquals(YT, tracker.currentPackage)
        assertEquals(MIN, tracker.flush(MIN)?.durationMs)

        // and the *open* activity's closer still ends the interval normally
        val closed = tracker.accept(BACKGROUND, YT, 2 * MIN, "PlayerActivity")
        assertEquals(MIN, closed?.durationMs)
        assertNull(tracker.currentPackage)
    }

    /** The other real-world ordering: the old activity pauses before the new one resumes. */
    @Test
    fun `pause-then-resume within one app fragments the interval but loses no time`() {
        tracker.accept(FOREGROUND, YT, 0, "MainActivity")
        val first = tracker.accept(BACKGROUND, YT, 5 * MIN, "MainActivity")
        assertEquals(5 * MIN, first?.durationMs)

        tracker.accept(FOREGROUND, YT, 5 * MIN, "PlayerActivity")
        val second = tracker.accept(BACKGROUND, YT, 10 * MIN, "PlayerActivity")
        assertEquals(5 * MIN, second?.durationMs)
    }

    @Test
    fun `a closer with no class information still closes by package`() {
        tracker.accept(FOREGROUND, YT, 0, "MainActivity")
        assertEquals(3 * MIN, tracker.accept(BACKGROUND, YT, 3 * MIN, null)?.durationMs)
    }

    @Test
    fun `a backwards clock jump cannot create negative or phantom usage`() {
        tracker.accept(FOREGROUND, YT, 10 * MIN)

        assertNull(tracker.accept(BACKGROUND, YT, 5 * MIN))
    }

    @Test
    fun `zero length intervals are dropped`() {
        tracker.accept(FOREGROUND, YT, MIN)

        assertNull(tracker.accept(BACKGROUND, YT, MIN))
    }
}
