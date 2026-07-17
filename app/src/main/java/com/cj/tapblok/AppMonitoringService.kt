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
import com.cj.tapblok.database.ScopeUsage
import com.cj.tapblok.database.rulesFor
import com.cj.tapblok.database.scopeIdOf
import com.cj.tapblok.usage.ForegroundTracker
import com.cj.tapblok.usage.LockState
import com.cj.tapblok.usage.UsageAccountant
import com.cj.tapblok.usage.UsageEventReader
import com.cj.tapblok.usage.UsageInterval
import com.cj.tapblok.usage.lockStateOf
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
    // Strict mode: apps granted a timed unlock, package -> expiry epoch millis.
    // Superseded by the persisted graceUntil in step 5; kept for now so the existing
    // NFC/QR unlock keeps working while the tag paths are still being reworked.
    private val temporarilyUnlockedApps = java.util.concurrent.ConcurrentHashMap<String, Long>()

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "app_monitoring_channel"
        const val ACTION_START_BREAK = "com.cj.tapblok.ACTION_START_BREAK"
        const val ACTION_UNLOCK_APP = "com.cj.tapblok.ACTION_UNLOCK_APP"
        const val EXTRA_UNLOCK_PACKAGE = "com.cj.tapblok.extra.UNLOCK_PACKAGE"
        private const val INITIAL_EVENT_LOOKBACK_MS = 60 * 60 * 1000L
        @Volatile var isRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getDatabase(this)
        prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
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
            intent.getStringExtra(EXTRA_UNLOCK_PACKAGE)?.let { unlockPackage ->
                val minutes = prefs.getInt(AppSettings.KEY_UNLOCK_MINUTES, AppSettings.DEFAULT_UNLOCK_MINUTES)
                temporarilyUnlockedApps[unlockPackage] = System.currentTimeMillis() + minutes * 60_000L
                Log.d("AppMonitoringService", "Temporarily unlocked $unlockPackage for $minutes minutes.")
            }
            return START_STICKY
        }

        Log.d("AppMonitoringService", "Service has started.")

        prefs.edit {
            putInt("breaks_remaining", prefs.getInt(AppSettings.KEY_BREAKS_ALLOWED, AppSettings.DEFAULT_BREAKS_ALLOWED))
            putInt("blocked_app_attempts", 0)
            putBoolean("monitoring_active", true)
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TapBlok is Active")
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

                    tracker.currentPackage
                        ?.takeIf { it != packageName && !isTemporarilyUnlocked(it) }
                        ?.let { foreground ->
                            blockedApps[foreground]?.let { app ->
                                if (lockStateFor(app, now) != LockState.ALLOWED) {
                                    showBlockScreen(localContext, foreground)
                                }
                            }
                        }
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
        val resetHour = AppSettings.dailyResetHour(this)
        val dao = db.usageDao()

        var usage = dao.get(scopeId) ?: ScopeUsage(scopeId = scopeId)
        usage = UsageAccountant.rollDailyIfNeeded(usage, now, resetHour)
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

    private suspend fun lockStateFor(app: BlockedApp, now: Long): LockState {
        val rules = rulesFor(app, app.groupId?.let { groups[it] }, AppSettings.defaults(this))
        val usage = db.usageDao().get(scopeIdOf(app)) ?: return LockState.ALLOWED
        return lockStateOf(usage, rules, now)
    }

    private fun showBlockScreen(context: Context, packageName: String) {
        val blockIntent = Intent(context, BlockingActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("BLOCKED_APP_PACKAGE_NAME", packageName)
        }
        startActivity(blockIntent)
        if (BuildConfig.DEBUG) Log.d("AppMonitoringService", "Blocked app detected: $packageName")

        prefs.edit { putInt("blocked_app_attempts", prefs.getInt("blocked_app_attempts", 0) + 1) }
    }

    private fun isTemporarilyUnlocked(packageName: String): Boolean {
        val expiry = temporarilyUnlockedApps[packageName] ?: return false
        if (expiry <= System.currentTimeMillis()) {
            temporarilyUnlockedApps.remove(packageName)
            return false
        }
        return true
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
        prefs.edit { putBoolean("monitoring_active", false) }
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

