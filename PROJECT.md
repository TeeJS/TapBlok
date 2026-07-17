# TapBlok — Per-App Usage Limits (fork: TeeJS/TapBlok)

**Status:** DRAFT — awaiting sign-off. No code written yet.
**Base:** v1.5.1, identical to upstream `cajdata/TapBlok` at time of fork.

---

## 1. What is the one thing this must do?

Enforce **per-app usage limits**, where each controlled app has its own independent
session budget, daily cap, and reset time — and a locked app is freed by **whichever
comes first**: tapping the NFC tag, or waiting out the reset timer.

Worked example: YouTube, 25 min session / 90 min reset. Use it continuously for 25
minutes and it locks. It's available again after either 90 minutes pass or the tag is
tapped.

## 2. What would be wrong if we shipped "working" software without it?

These are the non-negotiables. Shipping without any one of them means we shipped the
wrong app:

- **Limits are per-app, not global.** A single shared session budget across all blocked
  apps is not this feature. YouTube hitting its cap must not affect Instagram — *unless*
  they're deliberately grouped (§8), which is the one case where sharing is the point.
- **Defaults are a template, not a pool.** A global default of 25 minutes across four apps
  means four independent 25-minute budgets, not 25 shared.
- **Both unlock paths work independently.** A build where only NFC frees the lock is
  today's TapBlok. A build where only the timer frees it removes the point of the tag.
- **Time survives process death.** Usage counters and cooldowns must be correct across
  service restart, task-swipe, and reboot. Counters held in memory would make
  "swipe TapBlok away" a one-gesture daily-cap reset.
- **Usage means usage.** Time must not accrue while the screen is off, while the app is
  backgrounded, or while the block screen is covering it.
- **The daily cap is absolute.** Nothing but the daily rollover clears it.

## 3. What is explicitly off-limits as a workaround?

- **No global timers standing in for per-app ones.** The existing `isBreakActive` /
  `startBreak()` mechanism is global and is *not* a foundation for per-app work.
- **No in-memory time state.** Anything time-based goes in Room. `temporarilyUnlockedApps`
  (`AppMonitoringService.kt:37`) is the pattern to replace, not copy.
- **No sampling a sticky foreground variable to accrue usage.** See §7.
- **No routing strict mode through the automation broadcast surface.** `ScheduleReceiver`
  is exported, unauthenticated, and self-documents as "not a security boundary"
  (`ScheduleReceiver.kt:14`). Strict mode must not be disableable by a broadcast.
- **No GMS dependency.** The app is currently 100% Google-Play-Services-free. Location
  uses AOSP `LocationManager`.
- **Editing rules is not an unlock path.** The per-app rules editor locks while a session
  is running, exactly as app selection does today (`AppSelectionActivity.kt:254`).

## 4. Deployment target and backup

- **Backup:** covered by git. Work happens on a feature branch off `main`; `main` stays
  clean at upstream parity. An `upstream` remote is configured for pulling cajdata's fixes.
- **Deployment target:** GitHub releases on the `TeeJS/TapBlok` fork. Not Play, not
  F-Droid, no upstream PR to cajdata.

Consequences of releasing on the fork:

- **No Play policy review.** Nothing here needs a background-location declaration.
- **The no-GMS rule stays anyway**, but now as a preference rather than a constraint — the
  on-demand `LocationManager` approach in §6 doesn't need GMS regardless, so there's no
  reason to take the dependency.

### Fork identity: `applicationId` → `com.tj.tapblok`

The fork signs with your key, upstream signs with cajdata's. Sharing `applicationId` would
mean Android refuses to install your build over upstream (signature mismatch), forcing an
uninstall and wiping the blocklist. Changing `applicationId` sidesteps this entirely — the
two become different apps and can coexist.

`applicationId` and `namespace` are independent. Change only `applicationId`; leave
`namespace = "com.cj.tapblok"` so **no source file moves** and upstream merges stay clean.

| Location | Action |
|---|---|
| `build.gradle.kts:20` `applicationId` | → `com.tj.tapblok` |
| `build.gradle.kts:16` `namespace` | **unchanged** — keeps `R`/`BuildConfig` imports valid |
| `AndroidManifest.xml:6,84` permission | → `com.tj.tapblok.permission.START_MONITORING` — **mandatory** |
| `AndroidManifest.xml:65`, `NfcWriteActivity.kt:42` MIME | → `application/vnd.com.tj.tapblok` |
| `shortcuts.xml:12` `targetPackage` | → `com.tj.tapblok` — **mandatory**, hardcoded |
| `shortcuts.xml:13` `targetClass` | **unchanged** — follows `namespace` |
| `AndroidManifest.xml:125-126` schedule actions | → `com.tj.tapblok.*` (hygiene) |

Two of these are hard failures, not warnings:

- **Custom permission names are device-global.** Leaving `com.cj.tapblok.permission.START_MONITORING`
  while upstream is installed under a different signing key fails the install outright with
  `INSTALL_FAILED_DUPLICATE_PERMISSION`.
- **`shortcuts.xml` `targetPackage` is hardcoded**, not derived. Left alone, the launcher
  shortcut silently targets *upstream's* package.

**NFC MIME type is changed deliberately.** Keeping it means both apps match the same tag,
so with both installed every tap raises an app-chooser dialog. Cost of changing: existing
tags must be re-written once via `NfcWriteActivity` (~10 seconds).

Because `applicationId` changes, the v1→v2 Room migration is only ever exercised by your
own future builds. First install is always a clean DB.

## 5. How will we verify it is done?

Each of these is a manual acceptance test on a real device:

1. Set YouTube to 25/90. Use for 25 min continuously → block screen appears.
2. Wait 90 min without touching it → opens normally, full 25 min available again.
3. Re-lock it. Tap the tag → opens immediately (RESET mode), full 25 min available.
4. Set Instagram to 10/60 alongside. Lock YouTube → Instagram still opens freely.
5. Use YouTube 10 min, background it 91 min, reopen → full 25 min (idle reset).
6. Use YouTube 10 min, background it 30 min, reopen → 15 min remain (no reset).
7. Open YouTube, lock the screen for 30 min → no usage accrued.
8. Hit the block screen, poke at YouTube every 10 min for 90 min → still unlocks at 90
   min from the *lock*, not from the last poke.
9. Hit the daily cap → tag does nothing, and the block screen says why.
10. Lock YouTube, force-stop TapBlok, reopen → cooldown and counters intact.
11. Lock YouTube, reboot → cooldown and counters intact.
12. Away from home, strict mode does not apply. At home, it does.
13. Turn location off → treated as away.

---

## 6. Settled design decisions

| Decision | Answer |
|---|---|
| Reset time role | One number per app; governs **both** post-lock cooldown and idle reset |
| Tag semantics | **Configurable per app**: `SKIP_THE_WAIT` (default) or `GRACE_WINDOW` |
| Tag vs. daily cap | Tag **never** touches the daily cap |
| Tag while unlocked | No-op |
| Tag while locked | Cancels the running cooldown |
| Geofence approach | Native, AOSP `LocationManager`, **on-demand** — no GMS, no background location |
| Location unknown | Treated as **away** (strict mode does not apply) |
| Daily reset hour | **Custom, global setting** — not calendar midnight. See below. |
| Daily cap of 0 | Means **no daily cap** for that app |
| Rule source | Global defaults + **live inheritance**; `null` = inherit, not copy-on-add |
| Usage keying | By **scope** (group, else package) from v1 — see §8 |
| App groups | Shared-budget groups: **schema in v1, UI in v2** |
| Override inside a group | Not supported — group rules win entirely |
| Home location | Set by a "use current location" button in Settings |
| Home radius | 150m, with the accuracy gate below |
| Geofence scope | Gates **strict mode only**. Emergency override behaviour unchanged. |
| External stop in strict mode | **Ignored.** See §11. |
| Fork identity | `applicationId` → `com.tj.tapblok`. See §4. |

### Location fix acceptance

A cached fix is often cell-tower-derived and honestly reports ±500–2000m accuracy. Testing
"within 150m" against a ±1000m fix is testing noise — and with "unknown = away", noise
resolves silently to *strict mode off*. So fixes are filtered before use:

1. Reject any fix whose `accuracy` is worse than the radius (150m).
2. Reject any fix older than 5 minutes.
3. If the cached fix is rejected, request **one fresh fix, ~10s timeout**. TapBlok is
   foreground at this moment, so a brief spinner on the block screen is acceptable.
4. If that also fails → unknown → away.

This leans on Wi-Fi being on at home, where the network provider typically returns 20–50m.
With Wi-Fi off and GPS cold indoors, expect strict mode to read as away. **If that proves
too permissive in practice, the lever is a larger radius (300–500m), not better code.**

### Why the daily reset hour is not midnight

Peak doomscrolling is 00:00–02:00. A calendar-midnight rollover would hand out a fresh
daily budget at exactly the worst moment of the night — the cap would reinforce the
behaviour it exists to stop.

So the day boundary is a user-set hour (e.g. 04:00), and `dailyPeriodStart` is the most
recent occurrence of that hour, not `LocalDate.atStartOfDay()`. Scrolling at 01:00 draws
down the budget of the day that began at 04:00 *yesterday* — which is the point.

Implementation notes: store `dailyPeriodStart` as epoch millis; handle DST transitions
(a local hour can occur zero or twice); recompute the period if the user changes the hour
mid-day.

### Why the geofence needs no background location

Strict mode is only ever evaluated at two moments — when the block screen appears
(`BlockingActivity.kt:76`) and when a tag is scanned (`NfcHandlerActivity.kt:60`). Both are
Activities; TapBlok is in the foreground exactly when the answer matters. So we check
location on demand with `ACCESS_FINE_LOCATION` only. No `ACCESS_BACKGROUND_LOCATION`, no
geofence registration, no reboot re-registration, no OEM battery-optimizer failures.

**Accepted tradeoff:** "unknown = away" means disabling location switches strict mode off.
This is a deliberate safety-first choice (never be stranded away from the tag), accepted
with the emergency override as the backstop.

---

## 7. The core technical constraint

`getForegroundApp()` (`AppMonitoringService.kt:206`) consumes only `MOVE_TO_FOREGROUND`
events and **never clears `currentForegroundApp`**. It is sticky — it holds the last app
that came forward indefinitely. Lock the phone in YouTube and TapBlok still believes
YouTube is foreground while it sits in your pocket.

Harmless for today's binary blocking. **Fatal for usage accounting.**

Usage must be derived from the **event stream**, not by sampling that variable:

- Pair `ACTIVITY_RESUMED` / `MOVE_TO_FOREGROUND` with `ACTIVITY_PAUSED` /
  `ACTIVITY_STOPPED` to get real intervals.
- Consume `SCREEN_NON_INTERACTIVE` / `KEYGUARD_SHOWN` as interval terminators.
- Do not accrue while the block screen is up (test #8 — otherwise poking a blocked app
  keeps pushing the cooldown out and it never expires).

**Payoff:** `UsageStatsManager` retains the event log, so on service restart we replay
events since `lastEventTimestamp` and backfill the usage we missed. Process death becomes
recoverable rather than lossy.

---

## 8. Proposed data model

Room v1 → v2. Three ideas, in order: **global defaults**, **live inheritance**, and
**budget scopes**.

### Global defaults (SharedPreferences, via `AppSettings`)

`default_session_minutes = 25`, `default_daily_minutes = 0`, `default_reset_minutes = 90`,
`default_tag_unlock_mode = SKIP_THE_WAIT`, `default_grace_minutes = 5`, plus the global
`daily_reset_hour`.

These are a **template of values, not a shared pool.** A default of 25 across four apps
means four independent 25-minute budgets.

### Live inheritance: `null` means inherit

An app stores nothing unless explicitly overridden, and resolves against the defaults at
evaluation time. Change the default from 25 to 20 and every non-overridden app follows;
only pinned apps stay put.

**UI requirement:** an inherited 25 and a pinned 25 look identical. The override screen
must distinguish them ("25 min · default" / "25 min · custom") and offer reset-to-default,
or the feature is confusing. Adding an app stays a plain checkbox — no forced config.

### Budget scopes (groups are v2, the keying is v1)

Usage is keyed by **scope**, not package. An app's scope is its group if it has one, else
its own package name.

This is the whole reason groups are cheap later: every counter, cooldown, and tag path is
group-shaped from day one without groups existing. Ship v1 keyed by package and groups
become a re-key of every usage read/write plus a live-data migration plus a full re-test
of §5 — that's the version where "big lift" becomes true.

```kotlin
enum class TagUnlockMode { SKIP_THE_WAIT, GRACE_WINDOW }

@Entity(tableName = "blocked_apps")
data class BlockedApp(
    @PrimaryKey val packageName: String,
    val groupId: String? = null,          // null = standalone
    // Overrides. null = inherit from global defaults.
    // Ignored entirely when groupId != null — the group's rules win.
    val sessionMinutes: Int? = null,
    val dailyMinutes: Int? = null,        // 0 = no daily cap; null = inherit
    val resetMinutes: Int? = null,
    val tagUnlockMode: TagUnlockMode? = null,
    val graceMinutes: Int? = null
)

// Table lands in v1; the UI to populate it is v2.
@Entity(tableName = "app_groups")
data class AppGroup(
    @PrimaryKey val groupId: String,
    val name: String,                     // "Short video"
    val sessionMinutes: Int? = null,      // same inherit semantics
    val dailyMinutes: Int? = null,
    val resetMinutes: Int? = null,
    val tagUnlockMode: TagUnlockMode? = null,
    val graceMinutes: Int? = null
)

@Entity(tableName = "usage")
data class ScopeUsage(
    @PrimaryKey val scopeId: String,      // packageName if standalone, groupId if grouped
    val sessionUsedMs: Long,
    val dailyUsedMs: Long,
    val lastUsedAt: Long,                 // drives cooldown and idle reset alike
    val dailyPeriodStart: Long,           // which "day" dailyUsedMs belongs to
    val graceUntil: Long                  // 0 = none
)
```

### Resolution

```kotlin
fun scopeOf(app: BlockedApp): String = app.groupId ?: app.packageName

fun rulesFor(app: BlockedApp, group: AppGroup?, d: Defaults): ResolvedRules =
    if (group != null)
        ResolvedRules(group.sessionMinutes ?: d.session, group.dailyMinutes ?: d.daily, ...)
    else
        ResolvedRules(app.sessionMinutes ?: d.session, app.dailyMinutes ?: d.daily, ...)
```

**An app in a group uses the group's rules entirely.** No per-app override inside a group —
"TikTok gets its own 10 minutes but shares the group's 25" has no coherent answer to whose
budget those minutes came from. Standalone with own rules and budget, or grouped with the
group's. Want an exception? Take it out of the group.

Migration is `ADD COLUMN ... NULL` throughout — no default values needed, since null *is*
inherit. Flip `exportSchema = true` (`AppDatabase.kt:9`) and commit schemas so migrations
are testable.

### Lock evaluation for scope S at time T

Order matters — the daily cap is checked first, which is what makes it absolute:

1. `dailyUsedMs >= dailyLimit` → **BLOCK** (only the daily rollover clears this)
2. `graceUntil > T` → **ALLOW**
3. `sessionUsedMs >= sessionLimit` → **BLOCK**
4. → **ALLOW**

### Accrual

Foreground time accrues to **both** counters. Grace-window time still accrues to the daily
total — otherwise chained grace windows would bypass the daily cap. No accrual while
blocked.

### Reset

The cooldown and the idle reset **collapse into one rule**, because hitting the lock stops
usage and so `lastUsedAt` stops advancing:

> `sessionUsedMs = 0` when `T - lastUsedAt >= resetMinutes`

This is a consequence of the reset-time-does-double-duty decision, and it's why that
decision was a good one.

### Tag tap on blocked app X

Resolve X to its scope S, then:

- Daily cap hit → **no-op**, and the block screen must say so. Otherwise you walk to the
  tag, tap, nothing happens, and it reads as a bug.
- `SKIP_THE_WAIT` → `sessionUsedMs = 0`, `graceUntil = 0`
- `GRACE_WINDOW` → `graceUntil = T + graceMinutes`

**On a grouped app this unlocks the whole group** — one budget, one lock, one unlock. That
is the intent, not a side effect.

### What groups buy: substitution

Per-app limits alone don't stop the failure mode they exist for. Lock YouTube and open
TikTok, and the limit only chose *which* app got doomscrolled. A shared budget is the only
mechanism in this design that closes that. Because every member pushes the scope's shared
`lastUsedAt` forward, rotating between apps can't reset the group either.

---

## 9. Open questions

None. All decisions are settled — see §6 and §4.

Deferred by choice, revisit if reality disagrees:

- **Home radius may need widening.** 150m is a starting value. If strict mode reads as
  away too often (Wi-Fi off, GPS cold indoors), raise the radius rather than loosening the
  accuracy gate — loosening the gate reintroduces silent noise.
- **Geofence gates strict mode only.** The emergency override is untouched for now.
- **Automation still stops non-strict sessions.** The §11 fix is deliberately narrow.

---

## 10. Build order (revised from the handoff)

The original handoff's build order referenced `AppBlockingService` and an Accessibility
Service; neither exists. This replaces it.

0. **Fork identity + strict-mode hardening.** Change `applicationId` and the five strings
   in §4; make strict mode ignore external stop requests (§11). Small, self-contained, and
   both want to land before anything is built on top.
1. **Schema + migration** (§8). Greenfield, not a restructure — the current schema is one
   column.
2. **Event-stream usage tracker** (§7). The riskiest piece; build and verify it *alone*
   against tests 5–8 before anything depends on it.
3. **Defaults + override UI.** Global defaults go in `SettingsActivity` alongside the
   existing settings. `AppSelectionActivity` stays a checkbox list; adding an app needs no
   config. The per-app override screen is net-new but optional-path, and must show
   inherited-vs-custom and offer reset-to-default. Must lock while a session runs
   (`AppSelectionActivity.kt:254`) — otherwise "set YouTube to 999" walks around
   everything above.
4. **Lock evaluation** (§8) wired into the monitor loop, replacing the binary
   `foregroundApp in blockedApps` check.
5. **Tag paths.** Rework `NfcHandlerActivity.handleValidTag()` for both modes; retire the
   global `temporarilyUnlockedApps` map in favour of persisted `graceUntil`.
6. **Daily cap + rollover**, including the block-screen reason copy.
7. **Geofenced strict mode.** Last — it's independent of everything above.

### v2

8. **App groups UI.** Create/name/assign screen, and surfacing which budget an app draws
   from. Pure UI — no migration, no refactor, because §8 keys usage by scope from v1.

Acceptance tests for groups, deferred with the UI:

- Group "Short video" = TikTok + Instagram at 25/90. Use TikTok 15 min, Instagram 10 min
  → both lock. Neither had hit 25 alone.
- Rotate between all four members for 25 min total → still locks. Rotation doesn't reset.
- Tag tap on any member → unlocks all members.
- Group hits its daily cap → tag does nothing for any member.

---

## 11. The `ScheduleReceiver` hole, in plain terms

Android lets apps send each other messages. TapBlok has one of these message-handlers
sitting on the outside of the app, and one of the things you can say to it is
*"stop blocking."* TapBlok obeys. That handler is `ScheduleReceiver`.

It exists for a good reason: it's how Tasker, MacroDroid, and Samsung Routines start and
stop sessions, and it's how TapBlok's own scheduled-blocking alarms fire.

There's meant to be a lock on it — the "Allow automation apps" setting, off by default.
**The lock doesn't work.** The message can carry a flag saying "I'm TapBlok's own alarm
clock, not an outsider", and TapBlok takes that at face value (`ScheduleReceiver.kt:28-34`).
Anything can set that flag. So the setting protects nothing.

**What it means concretely:** any app on your phone, or one `adb` command, can end a
strict-mode session instantly. No tag. No waiting. Nothing in strict mode's code path is
consulted.

**Why upstream says that's fine** (`ScheduleReceiver.kt:14`): this is a self-control app.
Anyone determined enough to script their way past their own blocker could just uninstall
it — and no app can stop you uninstalling it. So the back door isn't much worse than the
front door.

**Why it might not be fine for you:** uninstalling is deliberate, effortful, and you'd
notice yourself doing it. A broadcast is something you set up *once*, in a weak moment,
and then it's a silent one-tap bypass forever. TapBlok's entire thesis is friction, and
those two have very different amounts of it.

**The fix — decided, in scope.** Not locking the receiver down: a signature-level
permission would kill the Tasker integration outright, since third-party apps can't hold
one. Instead, narrowly: **strict mode ignores external stop requests.** Scheduled alarms
and automation keep working normally in non-strict sessions; strict mode goes back to
meaning what it says. Lands in build step 0.

Explicitly *not* fixed: with strict mode off, any app can still stop a session. That's
upstream's accepted tradeoff and it stays accepted — the point of this change is that
strict mode is the one place the tradeoff isn't acceptable.
