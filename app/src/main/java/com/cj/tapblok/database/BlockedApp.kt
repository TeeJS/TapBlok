package com.cj.tapblok.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * An app the user has chosen to control, plus its optional rule overrides.
 *
 * Every rule field is nullable and `null` means **inherit the global default**, resolved at
 * evaluation time rather than copied in at insert time. Changing a default therefore moves
 * every app that hasn't been explicitly pinned. This is why adding an app stays a plain
 * checkbox: `BlockedApp(packageName = x)` is a complete, valid row.
 *
 * When [groupId] is non-null the app draws its rules and its budget from that group and
 * every override here is ignored — see [rulesFor]. An app is either standalone with its own
 * rules and budget, or grouped with the group's; there is no coherent middle, because
 * "TikTok gets its own 10 minutes but shares the group's 25" has no answer to whose budget
 * those minutes came from.
 */
@Entity(tableName = "blocked_apps")
data class BlockedApp(
    @PrimaryKey
    val packageName: String,

    /** Group whose rules and shared budget this app draws from; null = standalone. */
    val groupId: String? = null,

    /** Continuous-use budget in minutes. null = inherit. */
    val sessionMinutes: Int? = null,

    /** Cumulative daily cap in minutes; 0 = no daily cap. null = inherit. */
    val dailyMinutes: Int? = null,

    /** Cooldown after a lock, and the idle gap that clears a partial session. null = inherit. */
    val resetMinutes: Int? = null,

    /** null = inherit. */
    val tagUnlockMode: TagUnlockMode? = null,

    /** Only read when the resolved mode is [TagUnlockMode.GRACE_WINDOW]. null = inherit. */
    val graceMinutes: Int? = null
)
