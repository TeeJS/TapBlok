package com.cj.tapblok

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.cj.tapblok.database.Defaults
import com.cj.tapblok.database.TagUnlockMode
import com.cj.tapblok.ui.theme.TapBlokTheme
import kotlinx.coroutines.launch
import java.util.Locale

object AppSettings {
    const val PREFS_NAME = "app_prefs"

    const val KEY_OVERRIDE_ENABLED = "override_enabled"
    const val KEY_OVERRIDE_SECONDS = "override_seconds"
    const val KEY_BREAKS_ALLOWED = "breaks_allowed"
    const val KEY_STRICT_MODE = "strict_mode_enabled"
    const val KEY_QR_TOKEN = "qr_token"
    const val KEY_EXTERNAL_AUTOMATION = "external_automation_enabled"
    const val KEY_SCHEDULE_ENABLED = "schedule_enabled"
    const val KEY_SCHEDULE_START_MINUTES = "schedule_start_minutes"
    const val KEY_SCHEDULE_STOP_ENABLED = "schedule_stop_enabled"
    const val KEY_SCHEDULE_STOP_MINUTES = "schedule_stop_minutes"
    // Bit 0 = Monday … bit 6 = Sunday
    const val KEY_SCHEDULE_DAYS = "schedule_days"

    // Per-app rule template. Apps and groups store null to mean "inherit these", resolved
    // at evaluation time, so changing one of these moves every app the user hasn't pinned.
    const val KEY_DEFAULT_SESSION_MINUTES = "default_session_minutes"
    const val KEY_DEFAULT_DAILY_MINUTES = "default_daily_minutes"
    const val KEY_DEFAULT_RESET_MINUTES = "default_reset_minutes"
    const val KEY_DEFAULT_TAG_UNLOCK_MODE = "default_tag_unlock_mode"
    const val KEY_DEFAULT_GRACE_MINUTES = "default_grace_minutes"
    const val KEY_DAILY_RESET_MINUTES = "daily_reset_minutes"

    // Usage notice: the little "Used 5 of 15 min" chip over controlled apps during a session
    const val KEY_USAGE_NOTICE_ENABLED = "usage_notice_enabled"
    const val KEY_USAGE_NOTICE_INTERVAL_MIN = "usage_notice_interval_minutes"
    const val KEY_USAGE_NOTICE_DAILY = "usage_notice_show_daily"

    // Geofenced strict mode. Stored as strings because SharedPreferences has no Double.
    const val KEY_GEOFENCE_ENABLED = "geofence_enabled"
    const val KEY_HOME_LAT = "home_lat"
    const val KEY_HOME_LON = "home_lon"
    const val KEY_HOME_RADIUS_M = "home_radius_metres"

    const val DEFAULT_OVERRIDE_SECONDS = 90
    const val DEFAULT_BREAKS_ALLOWED = 3
    const val DEFAULT_START_MINUTES = 22 * 60
    const val DEFAULT_STOP_MINUTES = 7 * 60
    const val DEFAULT_DAYS_MASK = 0b1111111

    const val DEFAULT_SESSION_MINUTES = 25
    const val DEFAULT_DAILY_MINUTES = 0 // 0 = no daily cap
    const val DEFAULT_RESET_MINUTES = 90
    const val DEFAULT_GRACE_MINUTES = 5
    val DEFAULT_TAG_UNLOCK_MODE = TagUnlockMode.SKIP_THE_WAIT

    /**
     * 04:00, not midnight. Peak doomscrolling is 00:00–02:00, so a calendar rollover would
     * hand out a fresh daily budget at exactly the worst moment of the night — the cap would
     * reinforce the behaviour it exists to stop. With a 04:00 boundary, scrolling at 01:00
     * draws down the budget of the day that began at 04:00 *yesterday*.
     */
    const val DEFAULT_DAILY_RESET_MINUTES = 4 * 60

    fun prefs(context: Context): android.content.SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The template that apps and groups inherit from when their own fields are null. */
    fun defaults(context: Context): Defaults {
        val p = prefs(context)
        return Defaults(
            sessionMinutes = p.getInt(KEY_DEFAULT_SESSION_MINUTES, DEFAULT_SESSION_MINUTES),
            dailyMinutes = p.getInt(KEY_DEFAULT_DAILY_MINUTES, DEFAULT_DAILY_MINUTES),
            resetMinutes = p.getInt(KEY_DEFAULT_RESET_MINUTES, DEFAULT_RESET_MINUTES),
            tagUnlockMode = p.getString(KEY_DEFAULT_TAG_UNLOCK_MODE, null)
                ?.let { runCatching { TagUnlockMode.valueOf(it) }.getOrNull() }
                ?: DEFAULT_TAG_UNLOCK_MODE,
            graceMinutes = p.getInt(KEY_DEFAULT_GRACE_MINUTES, DEFAULT_GRACE_MINUTES)
        )
    }

    /**
     * 150m. Small enough to mean "at home" rather than "in the neighbourhood", but it leans on
     * Wi-Fi being on: the network provider typically returns 20–50m, comfortably inside it.
     * With Wi-Fi off and GPS cold indoors, expect fixes to be rejected as too coarse and
     * strict mode to read as away. If that happens too often the lever is a **larger radius**,
     * not a looser accuracy gate — loosening the gate just reintroduces silent noise.
     */
    const val DEFAULT_HOME_RADIUS_M = 150

    /** Minutes since local midnight at which the daily cap rolls over. */
    fun dailyResetMinutes(context: Context): Int =
        prefs(context).getInt(KEY_DAILY_RESET_MINUTES, DEFAULT_DAILY_RESET_MINUTES)

    const val DEFAULT_USAGE_NOTICE_INTERVAL_MIN = 5

    fun usageNoticeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_USAGE_NOTICE_ENABLED, false)

    /** Minutes between notices while using a controlled app; 0 = chip stays visible. */
    fun usageNoticeIntervalMinutes(context: Context): Int =
        prefs(context).getInt(KEY_USAGE_NOTICE_INTERVAL_MIN, DEFAULT_USAGE_NOTICE_INTERVAL_MIN)

    fun usageNoticeShowDaily(context: Context): Boolean =
        prefs(context).getBoolean(KEY_USAGE_NOTICE_DAILY, false)

    fun geofenceEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GEOFENCE_ENABLED, false)

    /** Home as (lat, lon), or null when never captured. */
    fun homeLocation(context: Context): Pair<Double, Double>? {
        val p = prefs(context)
        val lat = p.getString(KEY_HOME_LAT, null)?.toDoubleOrNull() ?: return null
        val lon = p.getString(KEY_HOME_LON, null)?.toDoubleOrNull() ?: return null
        return lat to lon
    }

    fun setHomeLocation(context: Context, lat: Double, lon: Double) {
        prefs(context).edit {
            putString(KEY_HOME_LAT, lat.toString())
            putString(KEY_HOME_LON, lon.toString())
        }
    }

    fun homeRadiusMetres(context: Context): Int =
        prefs(context).getInt(KEY_HOME_RADIUS_M, DEFAULT_HOME_RADIUS_M)

    /** "25 min", "1h 30m", or [zeroLabel] when minutes is 0 and a meaning was supplied. */
    fun formatMinutes(minutes: Int, zeroLabel: String? = null): String = when {
        minutes == 0 && zeroLabel != null -> zeroLabel
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60}h"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }

    fun formatMinutesOfDay(minutesOfDay: Int): String =
        String.format(Locale.US, "%02d:%02d", minutesOfDay / 60, minutesOfDay % 60)
}

class SettingsActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TapBlokTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Settings") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                            }
                        )
                    }
                ) { padding ->
                    SettingsScreen(modifier = Modifier.padding(padding))
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { AppSettings.prefs(context) }

    var isServiceActive by remember { mutableStateOf(isServiceRunning(context, AppMonitoringService::class.java)) }

    var strictMode by remember { mutableStateOf(prefs.getBoolean(AppSettings.KEY_STRICT_MODE, false)) }
    var overrideEnabled by remember { mutableStateOf(prefs.getBoolean(AppSettings.KEY_OVERRIDE_ENABLED, true)) }
    var overrideSeconds by remember { mutableStateOf(prefs.getInt(AppSettings.KEY_OVERRIDE_SECONDS, AppSettings.DEFAULT_OVERRIDE_SECONDS)) }
    var breaksAllowed by remember { mutableStateOf(prefs.getInt(AppSettings.KEY_BREAKS_ALLOWED, AppSettings.DEFAULT_BREAKS_ALLOWED)) }
    var externalAutomation by remember { mutableStateOf(prefs.getBoolean(AppSettings.KEY_EXTERNAL_AUTOMATION, false)) }
    var scheduleEnabled by remember { mutableStateOf(prefs.getBoolean(AppSettings.KEY_SCHEDULE_ENABLED, false)) }
    var startMinutes by remember { mutableStateOf(prefs.getInt(AppSettings.KEY_SCHEDULE_START_MINUTES, AppSettings.DEFAULT_START_MINUTES)) }
    var stopEnabled by remember { mutableStateOf(prefs.getBoolean(AppSettings.KEY_SCHEDULE_STOP_ENABLED, true)) }
    var stopMinutes by remember { mutableStateOf(prefs.getInt(AppSettings.KEY_SCHEDULE_STOP_MINUTES, AppSettings.DEFAULT_STOP_MINUTES)) }
    var daysMask by remember { mutableStateOf(prefs.getInt(AppSettings.KEY_SCHEDULE_DAYS, AppSettings.DEFAULT_DAYS_MASK)) }

    val scope = rememberCoroutineScope()
    var geofenceEnabled by remember { mutableStateOf(AppSettings.geofenceEnabled(context)) }
    var homeRadius by remember { mutableStateOf(AppSettings.homeRadiusMetres(context)) }
    var capturingHome by remember { mutableStateOf(false) }
    var homeStatus by remember {
        mutableStateOf(
            if (AppSettings.homeLocation(context) != null) "Set" else "Not set — strict mode won't apply"
        )
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* state re-reads on the next recomposition */ }

    val initialDefaults = remember { AppSettings.defaults(context) }
    var defaultSession by remember { mutableStateOf(initialDefaults.sessionMinutes) }
    var defaultDaily by remember { mutableStateOf(initialDefaults.dailyMinutes) }
    var defaultReset by remember { mutableStateOf(initialDefaults.resetMinutes) }
    var defaultGrace by remember { mutableStateOf(initialDefaults.graceMinutes) }
    var defaultTagMode by remember { mutableStateOf(initialDefaults.tagUnlockMode) }
    var dailyResetMinutes by remember { mutableStateOf(AppSettings.dailyResetMinutes(context)) }

    var noticeEnabled by remember { mutableStateOf(prefs.getBoolean(AppSettings.KEY_USAGE_NOTICE_ENABLED, false)) }
    var noticeInterval by remember { mutableStateOf(AppSettings.usageNoticeIntervalMinutes(context)) }
    var noticeDaily by remember { mutableStateOf(prefs.getBoolean(AppSettings.KEY_USAGE_NOTICE_DAILY, false)) }

    // Notification access is granted in system Settings, not stored by us, so re-read it on
    // return rather than tracking a preference that could drift out of sync with reality
    var mediaAccessGranted by remember { mutableStateOf(MediaPauser(context).isEnabled()) }
    val notificationAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        mediaAccessGranted = MediaPauser(context).isEnabled()
    }

    // All inputs lock while a session runs so settings can't be loosened mid-session
    val editable = !isServiceActive

    fun rescheduleAlarms() = ScheduleManager.reschedule(context, startIfInWindow = true)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (isServiceActive) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Settings are locked while a session is active.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        SettingsSection(title = "Default limits") {
            Text(
                text = "Every app you block uses these unless you give it its own. " +
                    "Each app gets its own separate budget — a 25 minute default across four " +
                    "apps means four independent 25 minute budgets, not 25 shared.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            MinutesRow(
                label = "Session limit",
                caption = "Continuous use before an app locks",
                minutes = defaultSession,
                enabled = editable,
                isDefault = true,
                onPicked = {
                    defaultSession = it
                    prefs.edit { putInt(AppSettings.KEY_DEFAULT_SESSION_MINUTES, it) }
                }
            )
            MinutesRow(
                label = "Daily cap",
                caption = "Total per day. Nothing but the daily reset clears this — not even the tag.",
                minutes = defaultDaily,
                enabled = editable,
                zeroLabel = "No cap",
                isDefault = true,
                onPicked = {
                    defaultDaily = it
                    prefs.edit { putInt(AppSettings.KEY_DEFAULT_DAILY_MINUTES, it) }
                }
            )
            MinutesRow(
                label = "Reset time",
                caption = "Wait this long and a locked app frees itself — and a part-used " +
                    "session clears too. One number, both jobs.",
                minutes = defaultReset,
                enabled = editable,
                isDefault = true,
                onPicked = {
                    defaultReset = it
                    prefs.edit { putInt(AppSettings.KEY_DEFAULT_RESET_MINUTES, it) }
                }
            )
            TimeRow(
                label = "Day starts at",
                minutesOfDay = dailyResetMinutes,
                enabled = editable,
                onTimePicked = {
                    dailyResetMinutes = it
                    prefs.edit { putInt(AppSettings.KEY_DAILY_RESET_MINUTES, it) }
                }
            )
            Text(
                text = "When the daily cap rolls over. Not midnight by default: late-night " +
                    "scrolling would get handed a fresh budget at exactly the wrong moment.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "What the tag does to a locked app",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = defaultTagMode == TagUnlockMode.SKIP_THE_WAIT,
                    enabled = editable,
                    onClick = {
                        defaultTagMode = TagUnlockMode.SKIP_THE_WAIT
                        prefs.edit { putString(AppSettings.KEY_DEFAULT_TAG_UNLOCK_MODE, defaultTagMode.name) }
                    },
                    label = { Text("Fresh session") }
                )
                FilterChip(
                    selected = defaultTagMode == TagUnlockMode.GRACE_WINDOW,
                    enabled = editable,
                    onClick = {
                        defaultTagMode = TagUnlockMode.GRACE_WINDOW
                        prefs.edit { putString(AppSettings.KEY_DEFAULT_TAG_UNLOCK_MODE, defaultTagMode.name) }
                    },
                    label = { Text("Timed unlock") }
                )
            }
            Text(
                text = when (defaultTagMode) {
                    TagUnlockMode.SKIP_THE_WAIT ->
                        "Tapping the tag does exactly what waiting out the reset does: the app " +
                            "is free again with a full session. The walk to the tag is the friction."
                    TagUnlockMode.GRACE_WINDOW ->
                        "Tapping the tag buys a short window, then the app locks again — the " +
                            "session budget is never cleared."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (defaultTagMode == TagUnlockMode.GRACE_WINDOW) {
                MinutesRow(
                    label = "Unlock window",
                    minutes = defaultGrace,
                    enabled = editable,
                    isDefault = true,
                    onPicked = {
                        defaultGrace = it
                        prefs.edit { putInt(AppSettings.KEY_DEFAULT_GRACE_MINUTES, it) }
                    }
                )
            }
        }

        SettingsSection(title = "Strict Mode") {
            SettingsSwitchRow(
                label = "Strict mode",
                caption = "A session can only be stopped by scanning with TapBlokPlus open. " +
                        "Unlocking a single blocked app works either way — see “What the tag does” above.",
                checked = strictMode,
                enabled = editable,
                onCheckedChange = {
                    strictMode = it
                    prefs.edit { putBoolean(AppSettings.KEY_STRICT_MODE, it) }
                }
            )
            if (strictMode) {
                Text(
                    text = "For maximum friction, combine with zero breaks and a disabled emergency override.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                SettingsSwitchRow(
                    label = "Only when I'm home",
                    caption = "Your tag lives at home. Away from it, strict mode would lock you " +
                        "out with no way to comply — so this relaxes it when you're not there.",
                    checked = geofenceEnabled,
                    enabled = editable,
                    onCheckedChange = {
                        geofenceEnabled = it
                        prefs.edit { putBoolean(AppSettings.KEY_GEOFENCE_ENABLED, it) }
                        if (it && !HomeGeofence.hasPermission(context)) {
                            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                    }
                )

                if (geofenceEnabled) {
                    if (!HomeGeofence.hasPermission(context)) {
                        Text(
                            text = "Location permission is needed. Without it, TapBlokPlus can't tell " +
                                "whether you're home, and strict mode won't apply.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Home", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = homeStatus,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            enabled = editable && !capturingHome,
                            onClick = {
                                capturingHome = true
                                scope.launch {
                                    val fix = HomeGeofence.captureHere(context)
                                    capturingHome = false
                                    homeStatus = if (fix != null) {
                                        AppSettings.setHomeLocation(context, fix.first, fix.second)
                                        "Set from your current location"
                                    } else {
                                        "Couldn't get a location accurate enough — try again near a window, with Wi-Fi on"
                                    }
                                }
                            }
                        ) {
                            Text(if (capturingHome) "Locating…" else "Use current")
                        }
                    }

                    MinutesRow(
                        label = "Radius",
                        caption = "How close counts as home, in metres",
                        minutes = homeRadius,
                        enabled = editable,
                        isDefault = homeRadius == AppSettings.DEFAULT_HOME_RADIUS_M,
                        unitLabel = "Metres",
                        maxValue = 10_000,
                        format = { "$it m" },
                        onPicked = {
                            homeRadius = it
                            prefs.edit { putInt(AppSettings.KEY_HOME_RADIUS_M, it) }
                        }
                    )
                    Text(
                        text = "Shown in metres, not minutes. 150m leans on Wi-Fi being on at " +
                            "home, where a fix is usually accurate to 20–50m. With Wi-Fi off and " +
                            "GPS cold indoors, fixes are often only accurate to 500m+ — TapBlokPlus " +
                            "rejects those rather than guess, and treats you as away. If that " +
                            "happens too often, raise this rather than trust a vaguer fix.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "If TapBlokPlus can't tell where you are, it treats you as away and " +
                            "strict mode doesn't apply — so you're never stranded. The trade-off " +
                            "is that turning location off also turns strict mode off.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        SettingsSection(title = "Emergency Override") {
            SettingsSwitchRow(
                label = "Enable emergency override",
                caption = "Hold-to-stop button shown during a session",
                checked = overrideEnabled,
                enabled = editable,
                onCheckedChange = {
                    overrideEnabled = it
                    prefs.edit { putBoolean(AppSettings.KEY_OVERRIDE_ENABLED, it) }
                }
            )
            if (overrideEnabled) {
                Text(
                    text = "Hold duration",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30 to "30s", 90 to "90s", 300 to "5m", 900 to "15m").forEach { (seconds, label) ->
                        FilterChip(
                            selected = overrideSeconds == seconds,
                            enabled = editable,
                            onClick = {
                                overrideSeconds = seconds
                                prefs.edit { putInt(AppSettings.KEY_OVERRIDE_SECONDS, seconds) }
                            },
                            label = { Text(label) }
                        )
                    }
                }
            } else {
                Text(
                    text = "With the override disabled, only your NFC tag or QR code can stop a session.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        SettingsSection(title = "Breaks") {
            Text(
                text = "Breaks allowed per session",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "None", 1 to "1", 3 to "3", 5 to "5").forEach { (count, label) ->
                    FilterChip(
                        selected = breaksAllowed == count,
                        enabled = editable,
                        onClick = {
                            breaksAllowed = count
                            prefs.edit { putInt(AppSettings.KEY_BREAKS_ALLOWED, count) }
                        },
                        label = { Text(label) }
                    )
                }
            }
        }

        SettingsSection(title = "Scheduled Blocking") {
            SettingsSwitchRow(
                label = "Start sessions on a schedule",
                caption = "Blocking begins automatically, no tag needed",
                checked = scheduleEnabled,
                enabled = editable,
                onCheckedChange = {
                    scheduleEnabled = it
                    prefs.edit { putBoolean(AppSettings.KEY_SCHEDULE_ENABLED, it) }
                    rescheduleAlarms()
                }
            )
            if (scheduleEnabled) {
                TimeRow(
                    label = "Start time",
                    minutesOfDay = startMinutes,
                    enabled = editable,
                    onTimePicked = {
                        startMinutes = it
                        prefs.edit { putInt(AppSettings.KEY_SCHEDULE_START_MINUTES, it) }
                        rescheduleAlarms()
                    }
                )
                SettingsSwitchRow(
                    label = "Auto-stop",
                    caption = "End the session automatically",
                    checked = stopEnabled,
                    enabled = editable,
                    onCheckedChange = {
                        stopEnabled = it
                        prefs.edit { putBoolean(AppSettings.KEY_SCHEDULE_STOP_ENABLED, it) }
                        rescheduleAlarms()
                    }
                )
                if (stopEnabled) {
                    TimeRow(
                        label = "Stop time",
                        minutesOfDay = stopMinutes,
                        enabled = editable,
                        onTimePicked = {
                            stopMinutes = it
                            prefs.edit { putInt(AppSettings.KEY_SCHEDULE_STOP_MINUTES, it) }
                            rescheduleAlarms()
                        }
                    )
                }
                Text(
                    text = "Days",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val dayLabels = listOf("M", "T", "W", "T", "F", "S", "S")
                    dayLabels.forEachIndexed { index, label ->
                        val bit = 1 shl index
                        FilterChip(
                            selected = daysMask and bit != 0,
                            enabled = editable,
                            onClick = {
                                daysMask = daysMask xor bit
                                prefs.edit { putInt(AppSettings.KEY_SCHEDULE_DAYS, daysMask) }
                                rescheduleAlarms()
                            },
                            label = { Text(label) }
                        )
                    }
                }
                if (daysMask == 0) {
                    Text(
                        text = "Select at least one day for the schedule to run.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        SettingsSection(title = "Usage notice") {
            SettingsSwitchRow(
                label = "Show usage notice",
                caption = "A small chip at the top of the screen showing how much time " +
                    "you've used. Only while a session is running and a controlled app is open.",
                checked = noticeEnabled,
                enabled = editable,
                onCheckedChange = {
                    noticeEnabled = it
                    prefs.edit { putBoolean(AppSettings.KEY_USAGE_NOTICE_ENABLED, it) }
                }
            )
            if (noticeEnabled) {
                MinutesRow(
                    label = "Notify every",
                    caption = "How often the notice reappears while you keep using the app",
                    minutes = noticeInterval,
                    enabled = editable,
                    zeroLabel = "Always visible",
                    isDefault = noticeInterval == AppSettings.DEFAULT_USAGE_NOTICE_INTERVAL_MIN,
                    onPicked = {
                        noticeInterval = it
                        prefs.edit { putInt(AppSettings.KEY_USAGE_NOTICE_INTERVAL_MIN, it) }
                    }
                )
                SettingsSwitchRow(
                    label = "Show daily limit",
                    caption = "On: “Used 5 of 15 session, 30 of 60 daily”. " +
                        "Off: “Used 5 of 15 min”.",
                    checked = noticeDaily,
                    enabled = editable,
                    onCheckedChange = {
                        noticeDaily = it
                        prefs.edit { putBoolean(AppSettings.KEY_USAGE_NOTICE_DAILY, it) }
                    }
                )
            }
        }

        SettingsSection(title = "Blocked media") {
            SettingsSwitchRow(
                label = "Pause media when blocked",
                caption = if (mediaAccessGranted) {
                    "On. A blocked app playing in picture-in-picture will be paused."
                } else {
                    "Off. A blocked app can keep playing in a picture-in-picture window. " +
                        "Needs notification access — TapBlokPlus reads no notifications, it only " +
                        "uses the pause control."
                },
                checked = mediaAccessGranted,
                // Locked mid-session like every other row: turning media-pause off during a
                // session would weaken enforcement, which is exactly what the session lock
                // exists to prevent.
                enabled = editable,
                onCheckedChange = {
                    notificationAccessLauncher.launch(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    )
                }
            )
        }

        SettingsSection(title = "Automation") {
            SettingsSwitchRow(
                label = "Allow automation apps",
                caption = "Tasker, MacroDroid, Samsung Routines and similar apps can start or stop " +
                        "sessions by broadcasting com.tj.tapblok.SCHEDULE_START or SCHEDULE_STOP. " +
                        "Strict mode ignores stop requests from these apps.",
                checked = externalAutomation,
                enabled = editable,
                onCheckedChange = {
                    externalAutomation = it
                    prefs.edit { putBoolean(AppSettings.KEY_EXTERNAL_AUTOMATION, it) }
                }
            )
        }
    }

    // Re-check the lock when returning to the screen (e.g. a scheduled session started)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isServiceActive = isServiceRunning(context, AppMonitoringService::class.java)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/** Shared with [AppOverrideActivity] so the two screens read as one app, not two. */
@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 16.dp)
        )
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content
            )
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    label: String,
    caption: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * A row whose value is a duration in minutes, edited through a free-text dialog.
 *
 * Chips would be more consistent with the older rows, but session/daily/reset need a wide
 * range and a handful of presets would quietly cap what the user can express. Anything the
 * app can enforce, they should be able to type.
 */
@Composable
fun MinutesRow(
    label: String,
    caption: String? = null,
    minutes: Int,
    enabled: Boolean,
    zeroLabel: String? = null,
    isDefault: Boolean = false,
    // Defaults describe a duration in minutes; radius overrides these to be a distance in
    // metres. Without them the radius would display "24h" and cap at 1440 — a duration format
    // and a minutes limit wrongly applied to a distance.
    unitLabel: String = "Minutes",
    maxValue: Int = 24 * 60,
    format: (Int) -> String = { AppSettings.formatMinutes(it, zeroLabel) },
    onPicked: (Int) -> Unit
) {
    var editing by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { editing = true }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            caption?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = format(minutes),
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
            // An inherited 25 and a pinned 25 look identical otherwise, which makes the whole
            // template idea impossible to reason about
            Text(
                text = if (isDefault) "default" else "custom",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (editing) {
        ValueDialog(
            title = label,
            initial = minutes,
            unitLabel = unitLabel,
            maxValue = maxValue,
            zeroLabel = zeroLabel,
            onDismiss = { editing = false },
            onPicked = { editing = false; onPicked(it) }
        )
    }
}

@Composable
private fun ValueDialog(
    title: String,
    initial: Int,
    unitLabel: String,
    maxValue: Int,
    zeroLabel: String?,
    onDismiss: () -> Unit,
    onPicked: (Int) -> Unit
) {
    var text by remember { mutableStateOf(initial.toString()) }
    val parsed = text.trim().toIntOrNull()
    val valid = parsed != null && parsed >= 0 && parsed <= maxValue

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(5) },
                    label = { Text(unitLabel) },
                    singleLine = true,
                    isError = !valid
                )
                zeroLabel?.let {
                    Text(
                        text = "0 = $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onPicked) }, enabled = valid) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** A one-field text prompt, used for naming and renaming groups. Trims and rejects blanks. */
@Composable
fun TextFieldDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    val trimmed = text.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(40) },
                singleLine = true,
                label = { Text("Name") }
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(trimmed) }, enabled = trimmed.isNotEmpty()) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun TimeRow(
    label: String,
    minutesOfDay: Int,
    enabled: Boolean,
    onTimePicked: (Int) -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) {
                TimePickerDialog(
                    context,
                    { _, hour, minute -> onTimePicked(hour * 60 + minute) },
                    minutesOfDay / 60,
                    minutesOfDay % 60,
                    true
                ).show()
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = AppSettings.formatMinutesOfDay(minutesOfDay),
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        )
    }
}
