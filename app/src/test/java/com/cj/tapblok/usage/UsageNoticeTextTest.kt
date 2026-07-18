package com.cj.tapblok.usage

import com.cj.tapblok.database.ResolvedRules
import com.cj.tapblok.database.TagUnlockMode
import org.junit.Assert.assertEquals
import org.junit.Test

private const val MIN = 60_000L

class UsageNoticeTextTest {

    private val rules = ResolvedRules(
        sessionMinutes = 15,
        dailyMinutes = 60,
        resetMinutes = 90,
        tagUnlockMode = TagUnlockMode.SKIP_THE_WAIT,
        graceMinutes = 5
    )

    @Test
    fun `session-only format`() {
        assertEquals(
            "Used 5 of 15 min",
            usageNoticeText(5 * MIN, 30 * MIN, rules, showDaily = false)
        )
    }

    @Test
    fun `daily format matches the spec example`() {
        assertEquals(
            "Used 5 of 15 session, 30 of 60 daily",
            usageNoticeText(5 * MIN, 30 * MIN, rules, showDaily = true)
        )
    }

    @Test
    fun `daily display without a daily cap degrades to a plain total`() {
        val uncapped = rules.copy(dailyMinutes = 0)
        assertEquals(
            "Used 5 of 15 session, 30 today",
            usageNoticeText(5 * MIN, 30 * MIN, uncapped, showDaily = true)
        )
    }

    @Test
    fun `minutes are floored, not rounded up`() {
        // 5:59 of use is still "5" — the chip must not claim time not yet spent
        assertEquals(
            "Used 5 of 15 min",
            usageNoticeText(5 * MIN + 59_000, 0, rules, showDaily = false)
        )
    }
}
