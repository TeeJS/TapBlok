package com.cj.tapblok

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class NfcHandlerActivity : ComponentActivity() {

    companion object {
        private const val STRICT_NOTIFICATION_ID = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("NfcHandlerActivity", "Activity launched by NFC intent.")
        handleNfcIntent()
    }

    /**
     * Each path finishes itself. Deciding what a tag means can now require a location fix, so
     * [handleValidTag] may go async — an unconditional finish() here would cancel its
     * coroutine and silently drop the tap.
     */
    private fun handleNfcIntent() {
        if (NfcAdapter.ACTION_NDEF_DISCOVERED != intent.action) {
            finish()
            return
        }

        val messages = intent.getParcelableArrayExtraCompat<NdefMessage>(NfcAdapter.EXTRA_NDEF_MESSAGES)
        if (messages.isNullOrEmpty()) {
            finish()
            return
        }

        val ndefMessage = messages[0] as NdefMessage
        if (ndefMessage.records.isEmpty()) {
            Log.w("NfcHandlerActivity", "NFC message has no records.")
            finish()
            return
        }

        if (String(ndefMessage.records[0].type, Charsets.UTF_8) != NfcWriteActivity.NFC_MIME_TYPE) {
            Log.w("NfcHandlerActivity", "Ignoring NFC tag with unexpected MIME type.")
            finish()
            return
        }

        Log.d("NfcHandlerActivity", "Valid TapBlok Plus NFC tag detected.")
        handleValidTag()
    }

    private fun handleValidTag() {
        if (!isServiceRunning(this, AppMonitoringService::class.java)) {
            startMonitoringService(this)
            Toast.makeText(this, "Monitoring started.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Snapshot what the user was looking at NOW, before anything suspends. The strict-mode
        // decision below can wait several seconds on a location fix, and AppForeground's
        // visibility grace is only 5s — judged after the wait, a legitimate "TapBlok Plus open, tag
        // tapped" stop looked like a background scan and was refused. What matters is the
        // moment of the tap, not the moment the geofence finishes thinking.
        val blockingVisible = AppForeground.isBlockingVisible()
        val blockedPackage = AppForeground.blockedPackage
        val mainVisible = AppForeground.isMainVisible()

        // Deciding strict mode can need a location fix, so it can't happen inline. The
        // activity is translucent and has no UI, so there's nothing to keep on screen while
        // it resolves.
        lifecycleScope.launch {
            routeTag(strictModeApplies(this@NfcHandlerActivity), blockingVisible, blockedPackage, mainVisible)
            finish()
        }
    }

    private fun routeTag(
        strictMode: Boolean,
        blockingVisible: Boolean,
        blockedPackage: String?,
        mainVisible: Boolean
    ) {
        when {
            // On a block screen the tag means "free this app" — one of the two unlock paths
            // the whole feature is built around, alongside waiting out the reset. This no
            // longer depends on strict mode: before per-app limits existed there was no app
            // lock to clear, so this branch was strict-only and a tap here stopped the entire
            // session instead. That would now be a much bigger hammer than the user asked for.
            blockingVisible && blockedPackage != null ->
                unlockBlockedApp(blockedPackage)
            // Anywhere else, the tag is a session-level control.
            !strictMode -> stopSession()
            // Stopping the whole session in strict mode requires TapBlok Plus itself to be open
            mainVisible -> stopSession()
            else -> {
                Log.w("NfcHandlerActivity", "Strict mode refused background scan — TapBlok Plus wasn't open at tap time.")
                Toast.makeText(this, "Strict mode: open TapBlok Plus, then scan again to stop.", Toast.LENGTH_LONG).show()
                showStrictModeNotification()
            }
        }
    }

    private fun stopSession() {
        stopService(Intent(this, AppMonitoringService::class.java))
        Toast.makeText(this, "Monitoring stopped.", Toast.LENGTH_SHORT).show()
    }

    /**
     * Applies the tag to the blocked app. What that means — a fresh session or a timed window
     * — is the app's own configured mode, resolved by the service; and if the app has hit its
     * daily cap it means nothing at all, because the tag never touches the daily total.
     */
    private fun unlockBlockedApp(blockedPackage: String) {
        val unlockIntent = Intent(this, AppMonitoringService::class.java).apply {
            action = AppMonitoringService.ACTION_UNLOCK_APP
            putExtra(AppMonitoringService.EXTRA_UNLOCK_PACKAGE, blockedPackage)
        }
        startService(unlockIntent)
        Toast.makeText(this, "Unlocked.", Toast.LENGTH_SHORT).show()
        // Bring the unblocked app forward; the block screen closes itself once hidden
        packageManager.getLaunchIntentForPackage(blockedPackage)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
        }
    }

    // NotificationManagerCompat.notify silently drops the notification when
    // POST_NOTIFICATIONS isn't granted; the toast above still explains what to do
    @SuppressLint("MissingPermission")
    private fun showStrictModeNotification() {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, AppMonitoringService.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Strict mode is on")
            .setContentText("Open TapBlok Plus, then scan your tag again to stop the session.")
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        val manager = NotificationManagerCompat.from(this)
        if (manager.areNotificationsEnabled()) {
            manager.notify(STRICT_NOTIFICATION_ID, notification)
        }
    }
}
