package com.cj.tapblok.usage

import com.cj.tapblok.database.ResolvedRules
import com.cj.tapblok.database.ScopeUsage
import com.cj.tapblok.database.TagUnlockMode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

private const val MIN = 60_000L

/** The daily boundary is minutes-since-midnight, so 04:00 is 240 — not 4. */
private const val FOUR_AM = 4 * 60

/** YouTube from the charter's worked example: 25 min session, 90 min reset, no daily cap. */
private val youtubeRules = ResolvedRules(
    sessionMinutes = 25,
    dailyMinutes = 0,
    resetMinutes = 90,
    tagUnlockMode = TagUnlockMode.SKIP_THE_WAIT,
    graceMinutes = 5
)

class UsageAccountantTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: TimeZone = utc): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(y, mo - 1, d, h, mi, 0)
        }.timeInMillis

    // ---- daily period boundary ----

    @Test
    fun `the day starts at the reset hour, not midnight`() {
        val nineAm = at(2026, 7, 16, 9, 0)

        assertEquals(at(2026, 7, 16, 4, 0), UsageAccountant.dailyPeriodStart(nineAm, FOUR_AM, utc))
    }

    /**
     * The reason the hour is configurable at all: 01:00 scrolling must bill to the day that
     * began at 04:00 yesterday, not get a fresh budget at midnight.
     */
    @Test
    fun `one am belongs to yesterday's budget, which is the whole point`() {
        val oneAm = at(2026, 7, 16, 1, 0)

        assertEquals(at(2026, 7, 15, 4, 0), UsageAccountant.dailyPeriodStart(oneAm, FOUR_AM, utc))
    }

    @Test
    fun `midnight to two am is one continuous period with the evening before`() {
        val elevenPm = at(2026, 7, 15, 23, 0)
        val oneAm = at(2026, 7, 16, 1, 0)

        assertEquals(
            UsageAccountant.dailyPeriodStart(elevenPm, FOUR_AM, utc),
            UsageAccountant.dailyPeriodStart(oneAm, FOUR_AM, utc)
        )
    }

    @Test
    fun `a reset hour of zero behaves like calendar midnight`() {
        val nineAm = at(2026, 7, 16, 9, 0)

        assertEquals(at(2026, 7, 16, 0, 0), UsageAccountant.dailyPeriodStart(nineAm, 0, utc))
    }

    @Test
    fun `exactly at the reset hour starts the new period`() {
        val fourAm = at(2026, 7, 16, 4, 0)

        assertEquals(fourAm, UsageAccountant.dailyPeriodStart(fourAm, FOUR_AM, utc))
    }

    // ---- daily rollover ----

    @Test
    fun `crossing the boundary clears the daily total but not the session`() {
        val before = at(2026, 7, 16, 3, 0)
        val after = at(2026, 7, 16, 5, 0)
        val usage = ScopeUsage(
            scopeId = "yt",
            sessionUsedMs = 10 * MIN,
            dailyUsedMs = 100 * MIN,
            dailyPeriodStart = UsageAccountant.dailyPeriodStart(before, FOUR_AM, utc)
        )

        val rolled = UsageAccountant.rollDailyIfNeeded(usage, after, FOUR_AM, utc)

        assertEquals(0, rolled.dailyUsedMs)
        assertEquals(10 * MIN, rolled.sessionUsedMs)
    }

    @Test
    fun `no rollover within the same period`() {
        val nine = at(2026, 7, 16, 9, 0)
        val ten = at(2026, 7, 16, 10, 0)
        val usage = ScopeUsage(
            scopeId = "yt",
            dailyUsedMs = 100 * MIN,
            dailyPeriodStart = UsageAccountant.dailyPeriodStart(nine, FOUR_AM, utc)
        )

        assertEquals(100 * MIN, UsageAccountant.rollDailyIfNeeded(usage, ten, FOUR_AM, utc).dailyUsedMs)
    }

    // ---- accrual ----

    @Test
    fun `accrual credits both counters and advances lastUsedAt`() {
        val usage = ScopeUsage(scopeId = "yt")

        val after = UsageAccountant.accrue(usage, 5 * MIN, 5 * MIN)

        assertEquals(5 * MIN, after.sessionUsedMs)
        assertEquals(5 * MIN, after.dailyUsedMs)
        assertEquals(5 * MIN, after.lastUsedAt)
    }

    // ---- the reset rule: cooldown and idle reset are one rule ----

    /** Charter acceptance test 6. */
    @Test
    fun `a 30 minute gap does not reset - 15 of 25 minutes remain`() {
        var usage = ScopeUsage(scopeId = "yt")
        usage = UsageAccountant.accrue(usage, 10 * MIN, 10 * MIN)

        val reopened = 40 * MIN // 30 minutes later
        usage = UsageAccountant.applyResetIfDue(usage, reopened, youtubeRules)

        assertEquals(10 * MIN, usage.sessionUsedMs)
        assertEquals(15 * MIN, youtubeRules.sessionLimitMs - usage.sessionUsedMs)
    }

    /** Charter acceptance test 5. */
    @Test
    fun `a 91 minute gap resets to a full fresh session`() {
        var usage = ScopeUsage(scopeId = "yt")
        usage = UsageAccountant.accrue(usage, 10 * MIN, 10 * MIN)

        val reopened = 101 * MIN // 91 minutes later
        usage = UsageAccountant.applyResetIfDue(usage, reopened, youtubeRules)

        assertEquals(0, usage.sessionUsedMs)
    }

    @Test
    fun `the reset boundary is inclusive at exactly the reset time`() {
        var usage = ScopeUsage(scopeId = "yt")
        usage = UsageAccountant.accrue(usage, 10 * MIN, 10 * MIN)

        usage = UsageAccountant.applyResetIfDue(usage, 10 * MIN + youtubeRules.resetMs, youtubeRules)

        assertEquals(0, usage.sessionUsedMs)
    }

    /**
     * The cooldown and the idle reset are the same rule, so waiting out a lock is expressed
     * entirely by lastUsedAt not advancing.
     */
    @Test
    fun `waiting out the cooldown after a lock unlocks and restores the full budget`() {
        var usage = ScopeUsage(scopeId = "yt")
        usage = UsageAccountant.accrue(usage, 25 * MIN, 25 * MIN)
        assertEquals(LockState.SESSION_LOCKED, lockStateOf(usage, youtubeRules, 25 * MIN))

        val ninetyLater = 25 * MIN + 90 * MIN
        usage = UsageAccountant.applyResetIfDue(usage, ninetyLater, youtubeRules)

        assertEquals(LockState.ALLOWED, lockStateOf(usage, youtubeRules, ninetyLater))
        assertEquals(0, usage.sessionUsedMs)
    }

    /**
     * Charter acceptance test 8, and the trap the design most depends on avoiding: if a
     * blocked app's foreground blips were credited, lastUsedAt would keep advancing and the
     * cooldown would never expire.
     */
    @Test
    fun `poking a locked app every ten minutes does not push the cooldown out`() {
        var usage = ScopeUsage(scopeId = "yt")
        usage = UsageAccountant.accrue(usage, 25 * MIN, 25 * MIN)
        val lockedAt = 25 * MIN

        // nine attempts to open it over the next 90 minutes, each correctly not accrued
        for (poke in 1..9) {
            val t = lockedAt + poke * 10 * MIN
            usage = UsageAccountant.applyResetIfDue(usage, t, youtubeRules)
        }

        // still exactly 90 minutes from the lock, not from the last poke
        val ninetyAfterLock = lockedAt + 90 * MIN
        usage = UsageAccountant.applyResetIfDue(usage, ninetyAfterLock, youtubeRules)

        assertEquals(0, usage.sessionUsedMs)
        assertEquals(LockState.ALLOWED, lockStateOf(usage, youtubeRules, ninetyAfterLock))
    }

    @Test
    fun `by contrast, accruing while locked would postpone the cooldown forever`() {
        var usage = ScopeUsage(scopeId = "yt")
        usage = UsageAccountant.accrue(usage, 25 * MIN, 25 * MIN)

        // the bug, reproduced deliberately: credit each poke
        for (poke in 1..9) {
            val t = 25 * MIN + poke * 10 * MIN
            usage = UsageAccountant.accrue(usage, 1000, t)
            usage = UsageAccountant.applyResetIfDue(usage, t, youtubeRules)
        }

        val ninetyAfterLock = 25 * MIN + 90 * MIN
        usage = UsageAccountant.applyResetIfDue(usage, ninetyAfterLock, youtubeRules)

        // never unlocks - which is why accrue() must not be called on a locked scope
        assertEquals(LockState.SESSION_LOCKED, lockStateOf(usage, youtubeRules, ninetyAfterLock))
    }

    // ---- lock evaluation ----

    @Test
    fun `the worked example - 25 minutes of youtube locks it`() {
        var usage = ScopeUsage(scopeId = "yt")
        usage = UsageAccountant.accrue(usage, 24 * MIN, 24 * MIN)
        assertEquals(LockState.ALLOWED, lockStateOf(usage, youtubeRules, 24 * MIN))

        usage = UsageAccountant.accrue(usage, MIN, 25 * MIN)
        assertEquals(LockState.SESSION_LOCKED, lockStateOf(usage, youtubeRules, 25 * MIN))
    }

    @Test
    fun `no daily cap means a zero daily limit never locks`() {
        val usage = ScopeUsage(scopeId = "yt", dailyUsedMs = 10_000 * MIN)

        assertEquals(LockState.ALLOWED, lockStateOf(usage, youtubeRules, 0))
    }

    @Test
    fun `the daily cap is checked first, so a grace window cannot override it`() {
        val capped = youtubeRules.copy(dailyMinutes = 60)
        val usage = ScopeUsage(
            scopeId = "yt",
            dailyUsedMs = 60 * MIN,
            graceUntil = 999 * MIN // an active grace window
        )

        assertEquals(LockState.DAILY_LOCKED, lockStateOf(usage, capped, 0))
    }

    @Test
    fun `a grace window suppresses a session lock but the counter still stands`() {
        var usage = ScopeUsage(scopeId = "yt")
        usage = UsageAccountant.accrue(usage, 25 * MIN, 25 * MIN)

        usage = UsageAccountant.grantGrace(usage, 25 * MIN, youtubeRules)

        assertEquals(LockState.ALLOWED, lockStateOf(usage, youtubeRules, 27 * MIN))
        // and re-locks when the window closes, because the session was never cleared
        assertEquals(LockState.SESSION_LOCKED, lockStateOf(usage, youtubeRules, 31 * MIN))
    }

    @Test
    fun `skip the wait tag is identical to having waited`() {
        var usage = ScopeUsage(scopeId = "yt")
        usage = UsageAccountant.accrue(usage, 25 * MIN, 25 * MIN)

        usage = UsageAccountant.clearSession(usage)

        assertEquals(LockState.ALLOWED, lockStateOf(usage, youtubeRules, 25 * MIN))
        assertEquals(0, usage.sessionUsedMs)
    }

    @Test
    fun `the tag never touches the daily total in either mode`() {
        val capped = youtubeRules.copy(dailyMinutes = 60)
        val usage = ScopeUsage(scopeId = "yt", sessionUsedMs = 25 * MIN, dailyUsedMs = 60 * MIN)

        assertEquals(60 * MIN, UsageAccountant.clearSession(usage).dailyUsedMs)
        assertEquals(60 * MIN, UsageAccountant.grantGrace(usage, 0, capped).dailyUsedMs)
        // and so it stays locked either way
        assertEquals(LockState.DAILY_LOCKED, lockStateOf(UsageAccountant.clearSession(usage), capped, 0))
    }

    // ---- shared budgets ----

    @Test
    fun `a shared scope locks from combined use that neither app reached alone`() {
        val group = youtubeRules // 25 min shared
        var usage = ScopeUsage(scopeId = "short-video")

        usage = UsageAccountant.accrue(usage, 15 * MIN, 15 * MIN) // TikTok
        assertEquals(LockState.ALLOWED, lockStateOf(usage, group, 15 * MIN))

        usage = UsageAccountant.accrue(usage, 10 * MIN, 25 * MIN) // Instagram
        assertEquals(LockState.SESSION_LOCKED, lockStateOf(usage, group, 25 * MIN))
    }

    @Test
    fun `rotating between members cannot reset a shared scope`() {
        val group = youtubeRules
        var usage = ScopeUsage(scopeId = "short-video")

        // four apps, 6 minutes each, rotating - every one advances the same lastUsedAt
        for (i in 1..4) {
            usage = UsageAccountant.accrue(usage, 6 * MIN, i * 6 * MIN)
            usage = UsageAccountant.applyResetIfDue(usage, i * 6 * MIN, group)
        }

        assertEquals(24 * MIN, usage.sessionUsedMs)
    }
}
