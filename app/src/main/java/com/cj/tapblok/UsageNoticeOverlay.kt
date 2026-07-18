package com.cj.tapblok

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * The little "Used 5 of 15 min" chip at the top of the screen — the piece of Digital
 * Wellbeing the user missed.
 *
 * A plain [TextView] in a TYPE_APPLICATION_OVERLAY window rather than anything Compose:
 * an overlay window has no lifecycle owner, and a text pill doesn't need one. The window is
 * untouchable and unfocusable, so it can't intercept anything the user does in the app
 * underneath.
 *
 * Reuses the SYSTEM_ALERT_WINDOW permission the block screen already requires — no new
 * grant. The monitor loop drives it from a background coroutine, so every public method
 * hops to the main thread itself; callers don't have to care.
 */
class UsageNoticeOverlay(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private var lastText: String? = null
    private val autoHide = Runnable { removeView() }

    /**
     * Shows the chip (or updates its text). [autoHideMs] null keeps it up until [hide] —
     * the "always visible" mode; otherwise it slips away after that delay, and every call
     * restarts the clock.
     */
    fun show(text: String, autoHideMs: Long?) {
        handler.post {
            val tv = view ?: createView()?.also { view = it } ?: return@post
            if (text != lastText) {
                tv.text = text
                lastText = text
            }
            handler.removeCallbacks(autoHide)
            if (autoHideMs != null) handler.postDelayed(autoHide, autoHideMs)
        }
    }

    fun hide() {
        handler.post {
            handler.removeCallbacks(autoHide)
            removeView()
        }
    }

    private fun createView(): TextView? {
        val tv = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            val h = dp(14)
            val v = dp(8)
            setPadding(h, v, h, v)
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0xE6202124.toInt()) // dark, slightly translucent
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(28)
        }
        return try {
            windowManager().addView(tv, params)
            tv
        } catch (e: Exception) {
            // Overlay permission revoked mid-session, or the window manager refused us.
            // The notice is informational — failing to show it must never break monitoring.
            Log.w(TAG, "Could not show usage notice", e)
            null
        }
    }

    private fun removeView() {
        view?.let {
            try {
                windowManager().removeView(it)
            } catch (_: Exception) {
                // already gone
            }
        }
        view = null
        lastText = null
    }

    private fun windowManager() = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "UsageNoticeOverlay"
    }
}
