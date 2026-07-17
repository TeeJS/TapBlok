package com.cj.tapblok

import android.service.notification.NotificationListenerService

/**
 * Deliberately empty.
 *
 * This class exists for one reason: `MediaSessionManager.getActiveSessions()` requires its
 * caller to be an *enabled notification listener* (or to hold the system-only
 * MEDIA_CONTENT_CONTROL permission). Being enabled is the key; reading notifications is not
 * the point, and TapBlok never does.
 *
 * That capability is what lets [MediaPauser] stop a blocked app that has dropped into
 * picture-in-picture and kept playing over the block screen — see PROJECT.md §10a. Nothing
 * in the app reads, inspects, stores or transmits notification content, and no override of
 * onNotificationPosted exists precisely so that stays obviously true to anyone auditing this
 * file.
 *
 * The permission is optional. Without it, blocking works exactly as before and only the PiP
 * hole remains open.
 */
class TapBlokNotificationListener : NotificationListenerService()
