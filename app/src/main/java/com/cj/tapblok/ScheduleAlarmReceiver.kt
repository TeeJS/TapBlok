package com.cj.tapblok

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives TapBlok Plus's own scheduled-blocking alarms, and nothing else.
 *
 * This receiver is not exported, so only ScheduleManager's PendingIntents can reach it.
 * That routing *is* the trust boundary: previously both alarms and external automation
 * arrived at the exported ScheduleReceiver and were told apart by an EXTRA_FROM_ALARM
 * boolean, which any caller could set. Splitting the two means an alarm is an alarm
 * because of where it landed, not because of what it claimed.
 *
 * Alarm-driven stops are honoured even in strict mode: a scheduled window is the user's
 * own prior instruction, and a strict 22:00–07:00 session that could never auto-stop
 * would be a bug, not a feature.
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ALARM_START = "com.tj.tapblok.ALARM_START"
        const val ACTION_ALARM_STOP = "com.tj.tapblok.ALARM_STOP"
        private const val TAG = "ScheduleAlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ALARM_START -> {
                if (!isServiceRunning(context, AppMonitoringService::class.java)) {
                    Log.d(TAG, "Starting session (schedule).")
                    startMonitoringService(context)
                }
            }
            ACTION_ALARM_STOP -> {
                Log.d(TAG, "Stopping session (schedule).")
                context.stopService(Intent(context, AppMonitoringService::class.java))
            }
            else -> return
        }
        // Chain the next occurrence; alarm fires are one-shot
        ScheduleManager.reschedule(context)
    }
}
