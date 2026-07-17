package com.cj.tapblok

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Entry point for external automation apps (Tasker, MacroDroid, Samsung Routines): they
 * broadcast SCHEDULE_START / SCHEDULE_STOP here when the user has enabled "Allow
 * automation apps" in Settings.
 *
 * This receiver is exported and unauthenticated, so anything it honours is something any
 * app on the device can do. Two consequences are load-bearing:
 *
 * 1. TapBlok's own schedule alarms deliberately do not arrive here — they target the
 *    non-exported ScheduleAlarmReceiver. The old EXTRA_FROM_ALARM boolean that
 *    distinguished them was settable by any caller, so it skipped the automation check
 *    below entirely.
 * 2. Strict mode ignores stop requests from this receiver. A strict session is meant to
 *    end only via the tag; honouring an unauthenticated broadcast would make that a
 *    promise the app cannot keep.
 *
 * With strict mode off, any app can still stop a session once automation is enabled.
 * That remains an accepted trade-off for a self-control app — the point of (2) is that
 * strict mode is the one place it isn't acceptable.
 */
class ScheduleReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SCHEDULE_START = "com.tj.tapblok.SCHEDULE_START"
        const val ACTION_SCHEDULE_STOP = "com.tj.tapblok.SCHEDULE_STOP"
        private const val TAG = "ScheduleReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = AppSettings.prefs(context)

        if (!prefs.getBoolean(AppSettings.KEY_EXTERNAL_AUTOMATION, false)) {
            Log.w(TAG, "External trigger ignored — automation is disabled in Settings.")
            return
        }

        when (intent.action) {
            ACTION_SCHEDULE_START -> {
                if (!isServiceRunning(context, AppMonitoringService::class.java)) {
                    Log.d(TAG, "Starting session (automation).")
                    startMonitoringService(context)
                }
            }
            ACTION_SCHEDULE_STOP -> {
                if (prefs.getBoolean(AppSettings.KEY_STRICT_MODE, false) &&
                    isServiceRunning(context, AppMonitoringService::class.java)
                ) {
                    Log.w(TAG, "Stop ignored — strict mode ends only via the tag.")
                    return
                }
                Log.d(TAG, "Stopping session (automation).")
                context.stopService(Intent(context, AppMonitoringService::class.java))
            }
        }
    }
}
