package com.cj.tapblok.usage

import com.cj.tapblok.database.ResolvedRules

/**
 * The text shown in the usage-notice chip.
 *
 * Two formats, chosen by the "show daily limit" setting:
 *  - off:  "Used 5 of 15 min"
 *  - on:   "Used 5 of 15 session, 30 of 60 daily"
 *
 * When daily display is on but the scope has no daily cap, there is no "of b" to show, so
 * the daily part degrades to a plain total: "Used 5 of 15 session, 30 today".
 *
 * Minutes are floored: 5:59 of use still reads "5". The chip is a gauge, not a stopwatch,
 * and rounding up would claim time the user hasn't spent yet.
 */
fun usageNoticeText(
    sessionUsedMs: Long,
    dailyUsedMs: Long,
    rules: ResolvedRules,
    showDaily: Boolean
): String {
    val sessionUsed = (sessionUsedMs / 60_000L).toInt()
    if (!showDaily) return "Used $sessionUsed of ${rules.sessionMinutes} min"

    val dailyUsed = (dailyUsedMs / 60_000L).toInt()
    return if (rules.hasDailyCap) {
        "Used $sessionUsed of ${rules.sessionMinutes} session, $dailyUsed of ${rules.dailyMinutes} daily"
    } else {
        "Used $sessionUsed of ${rules.sessionMinutes} session, $dailyUsed today"
    }
}
