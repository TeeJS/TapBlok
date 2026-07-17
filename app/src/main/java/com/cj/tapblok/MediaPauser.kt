package com.cj.tapblok

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/**
 * Stops a locked app that is still playing media where the block screen can't reach it.
 *
 * The problem this solves (PROJECT.md §10a): when `BlockingActivity` covers YouTube, YouTube
 * drops into a pinned picture-in-picture window and keeps playing straight over the top. PiP
 * is not a foreground activity, so `ForegroundTracker` never sees it — the app ends up both
 * unblocked and unbilled. The usage event stream cannot help: entering PiP emits
 * ACTIVITY_PAUSED, exactly like closing the app.
 *
 * Media sessions are the one signal available that says "this package is still playing right
 * now", and they come with transport controls, so the same capability both detects the
 * problem and fixes it.
 *
 * Scope is deliberately narrow: pause a scope that is *already locked*. It does not treat
 * playback as usage, because a media session reports "playing", not "visible" — it cannot
 * tell PiP video from screen-off background audio, and counting the latter would burn a
 * YouTube budget on podcasts in your pocket. That leaves one theoretical hole (drop to PiP
 * before ever hitting the limit and you never lock, so this never fires), accepted because
 * PiP is a two-inch window you cannot doomscroll in.
 *
 * Entirely optional. Without notification access every method here no-ops and blocking
 * behaves exactly as it did before.
 */
class MediaPauser(private val context: Context) {

    /** True once the user has granted notification access in Settings. */
    fun isEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /**
     * Pauses any currently-playing session belonging to a locked scope.
     *
     * Runs every tick, so pressing play again just gets paused again a second later — the
     * app is effectively dead rather than merely paused once.
     */
    fun pauseLocked(isLocked: (packageName: String) -> Boolean) {
        activeSessions().forEach { controller ->
            if (controller.playbackState?.state != PlaybackState.STATE_PLAYING) return@forEach
            if (!isLocked(controller.packageName)) return@forEach

            Log.d(TAG, "Pausing ${controller.packageName} — its scope is locked.")
            controller.transportControls.pause()
        }
    }

    private fun activeSessions(): List<MediaController> = try {
        val manager = context.getSystemService(MediaSessionManager::class.java)
        manager.getActiveSessions(ComponentName(context, TapBlokNotificationListener::class.java))
    } catch (e: SecurityException) {
        // Notification access revoked between the isEnabled() check and here, or never
        // granted. Not an error — the feature is opt-in.
        emptyList()
    }

    private companion object {
        const val TAG = "MediaPauser"
    }
}
