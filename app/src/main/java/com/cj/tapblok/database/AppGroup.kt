package com.cj.tapblok.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A set of apps that share one budget — e.g. "Short video" holding TikTok, Instagram,
 * RedNote and YouTube on a single 25-minute session and one daily cap.
 *
 * Groups exist because per-app limits alone don't stop the failure they're meant to stop:
 * lock YouTube and open TikTok, and the limit only chose which app got doomscrolled. A
 * shared budget is the only mechanism here that closes substitution. Rotating between
 * members can't reset a group either, since every member pushes the same scope's
 * `lastUsedAt` forward.
 *
 * Rule fields follow the same inherit-on-null semantics as [BlockedApp].
 *
 * The table lands now but nothing populates it yet: the create/assign UI is v2. Usage is
 * keyed by scope from day one (see [ScopeUsage]) precisely so that stays a UI-only change
 * rather than a re-key plus a live-data migration.
 */
@Entity(tableName = "app_groups")
data class AppGroup(
    @PrimaryKey
    val groupId: String,

    /** User-visible name, e.g. "Short video". */
    val name: String,

    /** null = inherit the global default. */
    val sessionMinutes: Int? = null,

    /** 0 = no daily cap. null = inherit. */
    val dailyMinutes: Int? = null,

    /** null = inherit. */
    val resetMinutes: Int? = null,

    /** null = inherit. */
    val tagUnlockMode: TagUnlockMode? = null,

    /** null = inherit. */
    val graceMinutes: Int? = null
)
