package com.cj.tapblok.database

/**
 * The global template every unpinned app and group inherits from. Held in SharedPreferences
 * (see `AppSettings.defaults`) rather than Room, because it is settings, not data.
 *
 * A template of *values*, not a shared pool: a default of 25 minutes across four apps means
 * four independent 25-minute budgets.
 */
data class Defaults(
    val sessionMinutes: Int,
    val dailyMinutes: Int,
    val resetMinutes: Int,
    val tagUnlockMode: TagUnlockMode,
    val graceMinutes: Int
)

/** Effective rules for a scope after inheritance has been applied. */
data class ResolvedRules(
    val sessionMinutes: Int,
    val dailyMinutes: Int,
    val resetMinutes: Int,
    val tagUnlockMode: TagUnlockMode,
    val graceMinutes: Int
) {
    /** 0 means the user asked for no daily ceiling on this scope. */
    val hasDailyCap: Boolean get() = dailyMinutes > 0

    val sessionLimitMs: Long get() = sessionMinutes * 60_000L
    val dailyLimitMs: Long get() = dailyMinutes * 60_000L
    val resetMs: Long get() = resetMinutes * 60_000L
    val graceMs: Long get() = graceMinutes * 60_000L
}

/**
 * Which budget this app draws from: its group's if it has one, otherwise its own.
 *
 * Every usage read and write goes through this, which is what lets groups arrive later
 * without touching the accounting.
 */
fun scopeIdOf(app: BlockedApp): String = app.groupId ?: app.packageName

/**
 * Resolves an app's effective rules.
 *
 * @param group the app's group, or null when standalone. When non-null the group's rules
 * win outright and the app's own overrides are ignored — a grouped app shares one budget,
 * so a per-app override of a shared limit has no meaning.
 */
fun rulesFor(app: BlockedApp, group: AppGroup?, defaults: Defaults): ResolvedRules =
    if (group != null) {
        ResolvedRules(
            sessionMinutes = group.sessionMinutes ?: defaults.sessionMinutes,
            dailyMinutes = group.dailyMinutes ?: defaults.dailyMinutes,
            resetMinutes = group.resetMinutes ?: defaults.resetMinutes,
            tagUnlockMode = group.tagUnlockMode ?: defaults.tagUnlockMode,
            graceMinutes = group.graceMinutes ?: defaults.graceMinutes
        )
    } else {
        ResolvedRules(
            sessionMinutes = app.sessionMinutes ?: defaults.sessionMinutes,
            dailyMinutes = app.dailyMinutes ?: defaults.dailyMinutes,
            resetMinutes = app.resetMinutes ?: defaults.resetMinutes,
            tagUnlockMode = app.tagUnlockMode ?: defaults.tagUnlockMode,
            graceMinutes = app.graceMinutes ?: defaults.graceMinutes
        )
    }
