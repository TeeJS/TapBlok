<p align="center">
  <img src="docs/icon.png" width="120" style="border-radius: 24px" />
</p>

<h1 align="center">TapBlokPlus</h1>

<p align="center">
  <strong>Per-app usage limits, unlocked by a physical tag.</strong>
</p>

<p align="center">
  A fork of <a href="https://github.com/cajdata/TapBlok">TapBlok</a>, reworked from a single
  all-or-nothing focus session into <strong>per-app usage limits</strong>. Give each app its
  own daily and per-session budget; when it runs out the app locks, and the only ways back in
  are tapping an NFC tag you've placed somewhere inconvenient â€” or waiting out a cooldown.
  No digital bypass, no "just this once."
</p>

<p align="center">Android 7.0+ &nbsp;Â·&nbsp; Apache 2.0 &nbsp;Â·&nbsp; Free, no subscription, no tracking</p>

---

## Why "Plus"

Upstream TapBlok blocks a chosen set of apps for the length of a focus session â€” all of them,
all at once, until you end the session. TapBlokPlus keeps that physical-tag philosophy but
changes the model underneath: **every controlled app carries its own budget.**

- Use YouTube for its 25-minute session limit â†’ *YouTube* locks. Instagram is untouched.
- Hit an app's daily cap â†’ it's done for the day; only the daily reset brings it back.
- A locked app frees itself once you've left it alone for its reset time â€” or instantly when
  you tap the tag.

It installs as a **separate app** (`com.tj.tapblok`) so it can live alongside the original.

---

## âœ¨ Features

### Per-app limits
- **â±ï¸ Session limit** â€” continuous-use budget per app (e.g. 25 min). Time only counts while
  the app is actually on screen â€” a locked phone in your pocket accrues nothing.
- **ðŸ“… Daily cap** â€” a cumulative daily ceiling per app. **Absolute:** nothing but the daily
  rollover clears it â€” not even the tag. The day boundary is a time *you* pick (default 04:00,
  not midnight, so late-night scrolling doesn't get a fresh budget at the worst moment).
- **ðŸ” Reset time** â€” one number that does double duty: the cooldown after a lock, and the
  idle gap that clears a part-used session.
- **ðŸŽšï¸ Defaults + overrides** â€” set global default limits once; every app inherits them live.
  Override any single app, and see at a glance whether a value is inherited or pinned.
- **ðŸ‘¥ App groups** â€” put several apps on **one shared budget** (e.g. a "Short video" group
  over TikTok, Instagram and YouTube). Closes the loophole of app-hopping to dodge a limit.

### Unlocking
- **ðŸ·ï¸ NFC tag** â€” tap any NDEF tag to free a locked app. Per app, the tag either starts a
  **fresh session** or grants a **timed unlock window**. The tag never touches the daily cap.
- **â³ Wait it out** â€” or just leave the app alone for its reset time; whichever comes first.
- **ðŸ“· QR code** â€” a printable per-install code as a tag alternative.

### Guardrails
- **ðŸ” Strict mode** â€” a session can only be stopped by scanning with TapBlokPlus open, and
  strict mode is hardened so an automation broadcast can't quietly end it.
- **ðŸ“ Geofenced strict mode** â€” because your tag lives at home, strict mode can be set to
  apply *only near home*, so you're never locked out with no way to comply while you're out.
  On-demand location only â€” no background tracking, no Google Play Services.
- **ðŸ“Ÿ Usage notice** â€” an optional on-screen chip ("Used 5 of 25 session, 30 of 120 daily")
  while a controlled app is open.
- **â¯ï¸ Pause blocked media** â€” optionally pause a blocked app that keeps playing in a
  picture-in-picture window (needs notification access; reads no notifications).
- **ðŸ”’ Safety list** â€” dialer, camera, settings and launcher can never be blocked.

### Kept from upstream
Smart breaks Â· boot persistence Â· emergency override Â· scheduled blocking Â· opt-in automation Â·
attempt counter Â· app shortcut.

### Companion API
Exposes read-only session state (`SessionStateProvider` + `SESSION_STARTED`/`STOPPED`
broadcasts) to apps signed with the same key, so a companion can enforce alongside a session.

---

## ðŸš€ Getting Started

1. **Build & install** â€” see [Building](#-building) below. (No published releases yet.)
2. **Grant permissions** â€” Usage Access and Display Over Other Apps. TapBlokPlus prompts you.
3. **Pick your apps** â€” "Manage Blocked Apps", then tap the tune icon on any app to set its limits.
4. **Set defaults** â€” Settings â†’ Default limits sets the template every app inherits.
5. **Set up unlock** â€” write an NFC tag in-app, or print a QR code.
6. **Start a session** â€” tap "Start Monitoring" and put the tag somewhere out of arm's reach.

### Automation (Tasker, MacroDroid, Samsung Routines)

Enable **Settings â†’ Allow automation apps**, then send a broadcast:

```
# Start a session
am broadcast -n com.tj.tapblok/com.cj.tapblok.ScheduleReceiver -a com.tj.tapblok.SCHEDULE_START

# Stop a session
am broadcast -n com.tj.tapblok/com.cj.tapblok.ScheduleReceiver -a com.tj.tapblok.SCHEDULE_STOP
```

External triggers are ignored unless the toggle is on, and **strict mode ignores stop
requests from automation** â€” only the tag ends a strict session.

> Note: the source package stayed `com.cj.tapblok` (to keep merges from upstream clean) while
> the installed app id is `com.tj.tapblok` â€” hence the mixed names in the component path above.

---

## ðŸ—ï¸ Building

Standard Gradle Android build. Kotlin 2.4.10 Â· AGP 8.13.2 Â· minSdk 24 Â· compileSdk 36.

```
./gradlew assembleDebug
```

For a signed release, add your keystore to `local.properties`:
`RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`.

---

## ðŸ§± Architecture

Kotlin Â· Jetpack Compose Â· Material 3 Â· Room Â· Coroutines Â· ZXing Â· Coil.

| Component | Purpose |
|---|---|
| `AppMonitoringService` | Foreground service â€” folds the usage-event stream into real intervals, bills each to its budget scope, and shows the block screen when a scope is out |
| `usage/` | Pure, unit-tested core: `ForegroundTracker`, `UsageAccountant`, lock evaluation |
| `AppSelectionActivity` / `AppOverrideActivity` | Pick apps; set per-app limits |
| `AppGroupsActivity` / `AppGroupEditActivity` | Shared-budget groups |
| `SettingsActivity` | Defaults, strict mode, geofence, usage notice, schedule, automation |
| `HomeGeofence` | On-demand "am I home?" for geofenced strict mode |
| `BlockingActivity` | Full-screen block screen; explains *why*, and whether the tag will help |
| `NfcHandlerActivity` / `NfcWriteActivity` / `QrCodeActivity` | Unlock credentials |
| `SessionStateProvider` | Signature-protected session-state API for companion apps |
| `database/` | Room schema v2 â€” `BlockedApp`, `AppGroup`, `ScopeUsage` |

Usage is keyed by **budget scope** (a group if the app is in one, else the app itself), which
is what lets one tag tap free a whole group and makes the shared-budget model work.

---

## ðŸ™ Upstream

Built on [cajdata/TapBlok](https://github.com/cajdata/TapBlok), which solved the hard
infrastructure â€” the permission flow, NFC read/write, the overlay block screen, boot
persistence, and the safety-list exclusions. TapBlokPlus tracks it as an upstream remote and
pulls its fixes.

## ðŸ“„ License

Apache 2.0 â€” see [LICENSE](LICENSE). Same license as upstream.
