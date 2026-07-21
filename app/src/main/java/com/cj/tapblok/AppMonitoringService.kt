package com.cj.tapblok

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.CountDownTimer
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.cj.tapblok.database.AppDatabase
import com.cj.tapblok.database.AppGroup
import com.cj.tapblok.database.BlockedApp
import com.cj.tapblok.database.ResolvedRules
import com.cj.tapblok.database.ScopeUsage
import com.cj.tapblok.database.TagUnlockMode
import com.cj.tapblok.database.rulesFor
import com.cj.tapblok.database.scopeIdOf
import com.cj.tapblok.usage.ForegroundTracker
import com.cj.tapblok.usage.LockState
import com.cj.tapblok.usage.UsageAccountant
import com.cj.tapblok.usage.UsageEventReader
import com.cj.tapblok.usage.UsageInterval
import com.cj.tapblok.usage.lockStateOf
import com.cj.tapblok.usage.usageNoticeText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AppMonitoringService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private lateinit var db: AppDatabase
    private lateinit var prefs: android.content.SharedPreferences
    @Volatile private var blockedApps: Map<String, BlockedApp> = emptyMap()
    @Volatile private var groups: Map<String, AppGroup> = emptyMap()
    @Volatile private var isBreakActive = false
    private var isMonitoring = false
    private var breakTimer: CountDownTimer? = null
    private lateinit var mediaPauser: MediaPauser
    private lateinit var noticeState: UsageNotice

    /**
     * Policy for the usage-notice chip; the pixels live in [UsageNoticeOverlay].
     *
     * Interval 0 keeps the chip visible for as long as a controlled app has focus. Any other
     * interval shows it briefly on entering the app and again every N minutes of continued
     * use. Leaving the app (or it getting blocked) hides the chip and resets the cycle, so
     * coming back always greets you with where you stand.
     */
    private class UsageNotice(private val overlay: UsageNoticeOverlay) {
        private var scope: String? = null
        private var lastShownAt = 0L

        fun tick(context: Context, scopeId: String, usage: ScopeUsage?, rules: ResolvedRules, now: Long) {
            if (!AppSettings.usageNoticeEnabled(context)) {
                hide()
                return
            }
            val text = usageNoticeText(
                usage?.sessionUsedMs ?: 0,
                usage?.dailyUsedMs ?: 0,
                rules,
                AppSettings.usageNoticeShowDaily(context)
            )
            val intervalMin = AppSettings.usageNoticeIntervalMinutes(context)
            if (intervalMin == 0) {
                overlay.show(text, null)
                scope = scopeId
            } else if (scope != scopeId || now - lastShownAt >= intervalMin * 60_000L) {
                overlay.show(text, AUTO_HIDE_MS)
                scope = scopeId
                lastShownAt = now
            }
        }

        fun hide() {
            overlay.hide()
            scope = null
        }

        private companion object {
            const val AUTO_HIDE_MS = 5_000L
        }
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "app_monitoring_channel"
        const val ACTION_START_BREAK = "com.cj.tapblok.ACTION_START_BREAK"
        const val ACTION_UNLOCK_APP = "com.cj.tapblok.ACTION_UNLOCK_APP"
        const val EXTRA_UNLOCK_PACKAGE = "com.cj.tapblok.extra.UNLOCK_PACKAGE"

        /** [LockState] name, so the block screen can say *why* — and whether the tag will help. */
        const val EXTRA_LOCK_REASON = "com.cj.tapblok.extra.LOCK_REASON"

        /**
         * Session lifecycle, pushed to same-signature companions (scroll-blocker) under
         * [SessionStateProvider.READ_PERMISSION]. Implicit broadcasts — a companion must
         * listen from a runtime-registered receiver and use [SessionStateProvider] for its
         * initial state; Android won't wake another app's manifest receiver for these.
         */
        const val ACTION_SESSION_STARTED = "com.tj.tapblok.SESSION_STARTED"
        const val ACTION_SESSION_STOPPED = "com.tj.tapblok.SESSION_STOPPED"
        private const val INITIAL_EVENT_LOOKBACK_MS = 60 * 60 * 1000L
        @Volatile var isRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getDatabase(this)
        prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        mediaPauser = MediaPauser(this)
        noticeState = UsageNotice(UsageNoticeOverlay(this))
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_BREAK) {
            startBreak()
            // The last onStartCommand return value governs restart-after-kill behaviour,
            // so a break must not downgrade the service to non-sticky
            return START_STICKY
        }

        if (intent?.action == ACTION_UNLOCK_APP) {
            intent.getStringExtra(EXTRA_UNLOCK_PACKAGE)?.let { pkg ->
                serviceScope.launch { applyTagUnlock(pkg) }
            }
            return START_STICKY
        }

        Log.d("AppMonitoringService", "Service has started.")

        prefs.edit {
            putInt("breaks_remaining", prefs.getInt(AppSettings.KEY_BREAKS_ALLOWED, AppSettings.DEFAULT_BREAKS_ALLOWED))
            putInt("blocked_app_attempts", 0)
            putBoolean("monitoring_active", true)
        }
        // Fires on service restarts too, not just fresh sessions — companions must treat it
        // as idempotent ("a session is in force"), not as an edge trigger
        sendBroadcast(Intent(ACTION_SESSION_STARTED), SessionStateProvider.READ_PERMISSION)

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TapBlokPlus is Active")
            .setContentText("App monitoring and blocking is running.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        if (isMonitoring) return START_STICKY
        isMonitoring = true

        serviceScope.launch {
            db.blockedAppDao().getAllBlockedApps().collect { list ->
                blockedApps = list.associateBy { it.packageName }
                if (BuildConfig.DEBUG) Log.d("AppMonitoringService", "Blocked apps updated from DB: ${blockedApps.keys}")
            }
        }

        serviceScope.launch {
            db.appGroupDao().observeAll().collect { list ->
                groups = list.associateBy { it.groupId }
            }
        }

        serviceScope.launch {
            val localContext = this@AppMonitoringService
            val tracker = ForegroundTracker()
            val reader = UsageEventReader(localContext)

            while (isActive) {
                if (!hasUsageStatsPermission(localContext) || !Settings.canDrawOverlays(localContext)) {
                    Log.e("AppMonitoringService", "Permissions revoked. Stopping service.")
                    stopSelf()
                    break
                }

                if (!isBreakActive) {
                    val now = System.currentTimeMillis()

                    // Fold the event log into real intervals of use, then bill each one.
                    // Intervals for apps we don't control are simply ignored.
                    reader.pump(tracker, now).forEach { interval -> accrue(interval, now) }

                    // A grace window is now part of the scope's persisted state, so
                    // lockStateOf already accounts for it — there is nothing extra to check
                    val foreground = tracker.currentPackage?.takeIf { it != packageName }
                    val app = foreground?.let { blockedApps[it] }
                    if (app != null) {
                        val rules = rulesFor(app, app.groupId?.let { groups[it] }, AppSettings.defaults(localContext))
                        val usage = db.usageDao().get(scopeIdOf(app))
                        val state = if (usage == null) LockState.ALLOWED else lockStateOf(usage, rules, now)
                        if (state != LockState.ALLOWED) {
                            noticeState.hide()
                            showBlockScreen(localContext, foreground, state)
                        } else {
                            noticeState.tick(localContext, scopeIdOf(app), usage, rules, now)
                        }
                    } else {
                        // No controlled app has focus — the chip must never outlive that
                        noticeState.hide()
                    }

                    // Deliberately not tied to the foreground check above: an app playing in
                    // picture-in-picture is by definition *not* foreground, which is exactly
                    // how it slips past the block screen (PROJECT.md §10a).
                    pauseLockedMedia(now)
                } else {
                    // Breaks suspend monitoring wholesale; a stale chip lingering over an
                    // unmonitored app would be a small lie
                    noticeState.hide()
                }
                delay(1000)
            }
        }

        return START_STICKY
    }

    /**
     * Credits one interval of foreground use to its budget scope.
     *
     * Refuses to accrue anything to a scope that is already locked. Every attempt to open a
     * blocked app produces a brief foreground blip before the block screen covers it, and
     * crediting those would keep pushing lastUsedAt forward — the cooldown would never
     * expire and a locked app would stay locked forever. Rolls and resets are still
     * persisted in that case, since they're what eventually unlock it.
     */
    private suspend fun accrue(interval: UsageInterval, now: Long) {
        val app = blockedApps[interval.packageName] ?: return
        val scopeId = scopeIdOf(app)
        val rules = rulesFor(app, app.groupId?.let { groups[it] }, AppSettings.defaults(this))
        val dao = db.usageDao()

        var usage = dao.get(scopeId) ?: ScopeUsage(scopeId = scopeId)
        usage = UsageAccountant.rollDailyIfNeeded(usage, now, AppSettings.dailyResetMinutes(this))
        usage = UsageAccountant.applyResetIfDue(usage, interval.startMs, rules)

        val state = lockStateOf(usage, rules, now)
        if (state == LockState.ALLOWED) {
            usage = UsageAccountant.accrue(usage, interval.durationMs, now)
        }
        dao.upsert(usage)

        if (BuildConfig.DEBUG) {
            Log.d(
                "AppMonitoringService",
                "${interval.packageName} -> $scopeId $state +${interval.durationMs}ms " +
                    "session=${usage.sessionUsedMs}/${rules.sessionLimitMs}ms daily=${usage.dailyUsedMs}ms"
            )
        }
    }

    /**
     * Applies a tag tap (or QR scan) to an app's budget scope.
     *
     * The tag never touches the daily total, in either mode. A scope that has hit its daily
     * cap is left exactly as it is, so the tag simply does nothing and only the daily rollover
     * frees it — that is what makes the daily cap the one absolute limit in the system. The
     * caller is told, so the block screen can say so rather than leaving the user tapping a
     * tag that will never work.
     *
     * On a grouped app this unlocks the whole group: one budget, one lock, one unlock. That is
     * the intent, not a side effect.
     */
    private suspend fun applyTagUnlock(packageName: String): LockState {
        val app = blockedApps[packageName] ?: return LockState.ALLOWED
        val scopeId = scopeIdOf(app)
        val rules = rulesFor(app, app.groupId?.let { groups[it] }, AppSettings.defaults(this))
        val now = System.currentTimeMillis()
        val dao = db.usageDao()

        var usage = dao.get(scopeId) ?: ScopeUsage(scopeId = scopeId)
        usage = UsageAccountant.rollDailyIfNeeded(usage, now, AppSettings.dailyResetMinutes(this))

        if (lockStateOf(usage, rules, now) == LockState.DAILY_LOCKED) {
            dao.upsert(usage)
            Log.d("AppMonitoringService", "Tag ignored for $packageName — daily cap reached.")
            return LockState.DAILY_LOCKED
        }

        usage = when (rules.tagUnlockMode) {
            // Exactly what waiting out the reset does: a full fresh session. The walk to
            // wherever the tag lives is the intended friction.
            TagUnlockMode.SKIP_THE_WAIT -> UsageAccountant.clearSession(usage)
            // A fixed window; the session counter is untouched, so it re-locks when the
            // window closes.
            TagUnlockMode.GRACE_WINDOW -> UsageAccountant.grantGrace(usage, now, rules)
        }
        dao.upsert(usage)

        Log.d("AppMonitoringService", "Tag applied to $scopeId (${rules.tagUnlockMode}).")
        return LockState.ALLOWED
    }

    /**
     * Pauses any locked app that is still playing media — the picture-in-picture case.
     *
     * No-ops unless the user has granted notification access, so the feature stays opt-in and
     * blocking behaves exactly as before without it.
     */
    private suspend fun pauseLockedMedia(now: Long) {
        if (!mediaPauser.isEnabled()) return

        // Resolve lock state up front: MediaPauser's callback is synchronous, and lock state
        // needs a suspending database read.
        val lockedPackages = blockedApps.values
            .filter { lockStateFor(it, now) != LockState.ALLOWED }
            .map { it.packageName }
            .toSet()

        if (lockedPackages.isEmpty()) return
        mediaPauser.pauseLocked { pkg -> pkg in lockedPackages }
    }

    private suspend fun lockStateFor(app: BlockedApp, now: Long): LockState {
        val rules = rulesFor(app, app.groupId?.let { groups[it] }, AppSettings.defaults(this))
        val usage = db.usageDao().get(scopeIdOf(app)) ?: return LockState.ALLOWED
        return lockStateOf(usage, rules, now)
    }

    private fun showBlockScreen(context: Context, packageName: String, reason: LockState) {
        val blockIntent = Intent(context, BlockingActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("BLOCKED_APP_PACKAGE_NAME", packageName)
            putExtra(EXTRA_LOCK_REASON, reason.name)
        }
        startActivity(blockIntent)
        if (BuildConfig.DEBUG) Log.d("AppMonitoringService", "Blocked app detected: $packageName")

        prefs.edit { putInt("blocked_app_attempts", prefs.getInt("blocked_app_attempts", 0) + 1) }
    }

    private fun startBreak() {
        breakTimer?.cancel()
        isBreakActive = true
        Log.d("AppMonitoringService", "Break started.")

        breakTimer = object : CountDownTimer(300000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
            }

            override fun onFinish() {
                isBreakActive = false
                Log.d("AppMonitoringService", "Break finished.")
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        noticeState.hide()
        prefs.edit { putBoolean("monitoring_active", false) }
        sendBroadcast(Intent(ACTION_SESSION_STOPPED), SessionStateProvider.READ_PERMISSION)
        serviceScope.cancel()
        breakTimer?.cancel()
        Log.d("AppMonitoringService", "Service has been destroyed.")
    }

    // Some launchers kill the process when the app's task is swiped away; schedule a
    // restart so an active session survives "clear all apps"
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (prefs.getBoolean("monitoring_active", false)) {
            val restartIntent = Intent(applicationContext, AppMonitoringService::class.java)
            val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(this, 1, restartIntent, flags)
            } else {
                PendingIntent.getService(this, 1, restartIntent, flags)
            }
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000, pendingIntent)
            Log.d("AppMonitoringService", "Task removed — scheduled service restart.")
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }
}

