package com.cj.tapblok.usage

import com.cj.tapblok.database.ResolvedRules
import com.cj.tapblok.database.ScopeUsage
import java.util.Calendar
import java.util.TimeZone

/**
 * Pure accounting rules over a scope's counters. No Android, no clock of its own — `now` is
 * always passed in, which is what makes every rule here directly testable.
 */
object UsageAccountant {

    /**
     * Start of the daily period containing [nowMs]: the most recent occurrence of
     * [resetMinutesOfDay] (minutes since local midnight, so 04:00 is `4 * 60`).
     *
     * Deliberately not midnight. Peak doomscrolling is 00:00–02:00, so a calendar rollover
     * would hand out a fresh daily budget at exactly the worst moment of the night. With a
     * 04:00 boundary, use at 01:00 draws down the budget of the day that began at 04:00
     * *yesterday*.
     *
     * Minutes rather than whole hours because the settings picker offers both, and silently
     * flooring the user's 04:30 to 04:00 would be a small lie.
     *
     * Uses [Calendar] rather than java.time because minSdk is 24 and core library
     * desugaring isn't enabled; ScheduleManager sets the same precedent.
     */
    fun dailyPeriodStart(nowMs: Long, resetMinutesOfDay: Int, timeZone: TimeZone = TimeZone.getDefault()): Long {
        val cal = Calendar.getInstance(timeZone).apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, resetMinutesOfDay / 60)
            set(Calendar.MINUTE, resetMinutesOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // Before today's boundary means we still belong to yesterday's period. Re-reading
        // timeInMillis after add() lets Calendar resolve DST gaps for us.
        if (cal.timeInMillis > nowMs) cal.add(Calendar.DAY_OF_YEAR, -1)
        return cal.timeInMillis
    }

    /**
     * Rolls the daily counter over if [now] has crossed into a new daily period.
     *
     * Only the rollover clears the daily total — no tag, in either mode, can touch it. That
     * is what makes the daily cap the one absolute limit in the system.
     */
    fun rollDailyIfNeeded(usage: ScopeUsage, now: Long, resetMinutesOfDay: Int, timeZone: TimeZone = TimeZone.getDefault()): ScopeUsage {
        val period = dailyPeriodStart(now, resetMinutesOfDay, timeZone)
        return if (period != usage.dailyPeriodStart) {
            usage.copy(dailyUsedMs = 0, dailyPeriodStart = period)
        } else {
            usage
        }
    }

    /**
     * Clears the session counter once the scope has been untouched for the reset time.
     *
     * The post-lock cooldown and the idle reset are the *same rule*. Hitting the lock stops
     * usage, so `lastUsedAt` stops advancing at the moment of the lock; "90 minutes since
     * last use" therefore expresses both "you waited out the cooldown" and "you ignored it
     * long enough to earn a fresh session". One timestamp, one rule, both behaviours.
     *
     * This only holds as long as nothing advances `lastUsedAt` while the scope is locked —
     * see [accrue].
     */
    fun applyResetIfDue(usage: ScopeUsage, now: Long, rules: ResolvedRules): ScopeUsage =
        if (usage.sessionUsedMs > 0 && now - usage.lastUsedAt >= rules.resetMs) {
            usage.copy(sessionUsedMs = 0)
        } else {
            usage
        }

    /**
     * Credits [durationMs] of foreground time to a scope.
     *
     * Time accrues to both counters. Grace-window time still counts against the daily total —
     * otherwise chaining grace windows would walk straight around the daily cap, and the tag
     * is not supposed to touch it.
     *
     * Callers must not accrue time for a scope that is currently locked. Every attempt to
     * open a blocked app produces a brief foreground blip before the block screen covers it;
     * crediting those would keep pushing `lastUsedAt` forward and the cooldown would never
     * expire — poke a locked app every ten minutes and it would stay locked forever.
     */
    fun accrue(usage: ScopeUsage, durationMs: Long, now: Long): ScopeUsage =
        if (durationMs <= 0) {
            usage
        } else {
            usage.copy(
                sessionUsedMs = usage.sessionUsedMs + durationMs,
                dailyUsedMs = usage.dailyUsedMs + durationMs,
                lastUsedAt = now
            )
        }

    /** A SKIP_THE_WAIT tag: exactly what waiting out the reset does. Daily is untouched. */
    fun clearSession(usage: ScopeUsage): ScopeUsage =
        usage.copy(sessionUsedMs = 0, graceUntil = 0)

    /** A GRACE_WINDOW tag: access for a fixed window; the session counter stays where it is. */
    fun grantGrace(usage: ScopeUsage, now: Long, rules: ResolvedRules): ScopeUsage =
        usage.copy(graceUntil = now + rules.graceMs)
}

/** Why a scope is blocked, or that it isn't. The block screen needs the reason, not just a bool. */
enum class LockState {
    /** Under budget, or inside a grace window. */
    ALLOWED,

    /** Session budget exhausted. Clears via the reset timer or the tag. */
    SESSION_LOCKED,

    /** Daily cap reached. Only the daily rollover clears this — the tag will not help. */
    DAILY_LOCKED
}

/**
 * Decides whether a scope may run right now.
 *
 * Order is load-bearing. The daily cap is checked *first*, which is precisely what makes it
 * absolute: a grace window cannot override it, and neither can a tag.
 */
fun lockStateOf(usage: ScopeUsage, rules: ResolvedRules, now: Long): LockState = when {
    rules.hasDailyCap && usage.dailyUsedMs >= rules.dailyLimitMs -> LockState.DAILY_LOCKED
    usage.graceUntil > now -> LockState.ALLOWED
    usage.sessionUsedMs >= rules.sessionLimitMs -> LockState.SESSION_LOCKED
    else -> LockState.ALLOWED
}
