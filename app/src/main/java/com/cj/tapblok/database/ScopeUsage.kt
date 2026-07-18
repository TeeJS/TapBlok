package com.cj.tapblok.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Accumulated usage for one **budget scope** — a group if the app belongs to one, otherwise
 * the app's own package name (see [scopeIdOf]).
 *
 * Keying by scope rather than package is what makes v2's shared-budget groups a UI change
 * instead of a re-key of every read and write plus a migration of live counters.
 *
 * This lives in Room and not in memory on purpose. `AppMonitoringService` is START_STICKY
 * and reschedules itself from `onTaskRemoved`, so it routinely comes back with fresh
 * process state; the old in-memory `temporarilyUnlockedApps` map simply forgot its unlocks
 * on restart. Holding a cooldown that way would silently re-lock an app, and holding daily
 * usage that way would make "swipe TapBlok Plus off recents" a one-gesture daily-cap reset.
 *
 * All timestamps are `System.currentTimeMillis()` epoch millis. That means moving the device
 * clock forward can skip a cooldown or roll the day over early. For a self-control app
 * that's a known, accepted softness rather than an oversight — `elapsedRealtime()` resists
 * clock changes but resets on reboot, so resisting it properly means storing both and
 * cross-checking. Not worth it until it's a real problem.
 */
@Entity(tableName = "usage")
data class ScopeUsage(
    /** Group id for grouped apps, package name for standalone ones. */
    @PrimaryKey
    val scopeId: String,

    /** Continuous-use accumulator, cleared by the reset rule or a SKIP_THE_WAIT tag. */
    val sessionUsedMs: Long = 0,

    /** Cumulative use within the current daily period. Cleared only by the rollover. */
    val dailyUsedMs: Long = 0,

    /**
     * Last moment this scope was actually in use. Drives the cooldown *and* the idle reset,
     * which are the same rule: hitting the lock stops usage, so this stops advancing at the
     * moment of the lock. Must not advance while the block screen is up, or repeatedly
     * poking a blocked app would push the cooldown out forever.
     */
    val lastUsedAt: Long = 0,

    /** Start of the daily period [dailyUsedMs] belongs to; the most recent daily-reset hour. */
    val dailyPeriodStart: Long = 0,

    /** Expiry of a GRACE_WINDOW unlock; 0 = none. */
    val graceUntil: Long = 0
)
