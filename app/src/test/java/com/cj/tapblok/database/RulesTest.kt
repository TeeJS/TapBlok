package com.cj.tapblok.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {

    private val defaults = Defaults(
        sessionMinutes = 25,
        dailyMinutes = 0,
        resetMinutes = 90,
        tagUnlockMode = TagUnlockMode.SKIP_THE_WAIT,
        graceMinutes = 5
    )

    @Test
    fun `app with no overrides inherits every default`() {
        val rules = rulesFor(BlockedApp(packageName = "com.youtube"), null, defaults)

        assertEquals(25, rules.sessionMinutes)
        assertEquals(0, rules.dailyMinutes)
        assertEquals(90, rules.resetMinutes)
        assertEquals(TagUnlockMode.SKIP_THE_WAIT, rules.tagUnlockMode)
        assertEquals(5, rules.graceMinutes)
    }

    @Test
    fun `overridden fields win and the rest still inherit`() {
        val app = BlockedApp(
            packageName = "com.youtube",
            sessionMinutes = 10,
            tagUnlockMode = TagUnlockMode.GRACE_WINDOW
        )

        val rules = rulesFor(app, null, defaults)

        assertEquals(10, rules.sessionMinutes)
        assertEquals(TagUnlockMode.GRACE_WINDOW, rules.tagUnlockMode)
        // untouched fields keep following the template
        assertEquals(90, rules.resetMinutes)
        assertEquals(5, rules.graceMinutes)
    }

    @Test
    fun `changing a default moves an unpinned app but not a pinned one`() {
        val unpinned = BlockedApp(packageName = "com.instagram")
        val pinned = BlockedApp(packageName = "com.youtube", sessionMinutes = 25)

        val tightened = defaults.copy(sessionMinutes = 20)

        assertEquals(20, rulesFor(unpinned, null, tightened).sessionMinutes)
        assertEquals(25, rulesFor(pinned, null, tightened).sessionMinutes)
    }

    @Test
    fun `group rules win outright and app overrides are ignored`() {
        val app = BlockedApp(
            packageName = "com.tiktok",
            groupId = "short-video",
            sessionMinutes = 10, // must not leak through
            resetMinutes = 5
        )
        val group = AppGroup(groupId = "short-video", name = "Short video", sessionMinutes = 25)

        val rules = rulesFor(app, group, defaults)

        assertEquals(25, rules.sessionMinutes)
        // the app's resetMinutes=5 override is ignored; the group inherits the default
        assertEquals(90, rules.resetMinutes)
    }

    @Test
    fun `group with null fields inherits the defaults, not the app's overrides`() {
        val app = BlockedApp(packageName = "com.tiktok", groupId = "short-video", sessionMinutes = 3)
        val group = AppGroup(groupId = "short-video", name = "Short video")

        assertEquals(25, rulesFor(app, group, defaults).sessionMinutes)
    }

    @Test
    fun `scope is the package when standalone and the group when grouped`() {
        assertEquals("com.youtube", scopeIdOf(BlockedApp(packageName = "com.youtube")))
        assertEquals(
            "short-video",
            scopeIdOf(BlockedApp(packageName = "com.tiktok", groupId = "short-video"))
        )
    }

    @Test
    fun `grouped apps share one scope, which is what makes the budget shared`() {
        val tiktok = BlockedApp(packageName = "com.tiktok", groupId = "short-video")
        val instagram = BlockedApp(packageName = "com.instagram", groupId = "short-video")

        assertEquals(scopeIdOf(tiktok), scopeIdOf(instagram))
    }

    @Test
    fun `zero daily minutes means no cap rather than an instant lock`() {
        val rules = rulesFor(BlockedApp(packageName = "com.youtube"), null, defaults)
        assertFalse(rules.hasDailyCap)

        val capped = rulesFor(BlockedApp(packageName = "com.youtube", dailyMinutes = 60), null, defaults)
        assertTrue(capped.hasDailyCap)
        assertEquals(60 * 60_000L, capped.dailyLimitMs)
    }

    @Test
    fun `minute fields convert to millis`() {
        val rules = rulesFor(BlockedApp(packageName = "com.youtube"), null, defaults)
        assertEquals(25 * 60_000L, rules.sessionLimitMs)
        assertEquals(90 * 60_000L, rules.resetMs)
        assertEquals(5 * 60_000L, rules.graceMs)
    }
}
