package com.screentamer.agent.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.screentamer.agent.R

/**
 * Full-screen lock overlay shown when screen time is up or a parent locks the TV.
 * Requires SYSTEM_ALERT_WINDOW (granted via adb: appops set ... allow).
 * All window operations are posted to the main thread (WindowManager needs a Looper).
 */
class LockOverlay(private val context: Context) {

    companion object {
        private const val TAG = "LockOverlay"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var view: View? = null

    val isShowing: Boolean
        get() = view != null

    val canDrawOverlays: Boolean
        get() = Settings.canDrawOverlays(context)

    fun show(message: String = "") {
        mainHandler.post {
            if (view != null) return@post
            if (!canDrawOverlays) {
                Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted; cannot show overlay")
                return@post
            }
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.OPAQUE
                )
                lp.gravity = Gravity.TOP or Gravity.START

                val v = LayoutInflater.from(context).inflate(R.layout.overlay_lock, null)
                if (message.isNotBlank()) {
                    v.findViewById<TextView>(R.id.overlayMsg)?.text = message
                }
                wm.addView(v, lp)
                view = v
                Log.i(TAG, "lock overlay shown")
            } catch (e: Exception) {
                Log.w(TAG, "overlay failed: ${e.message}")
            }
        }
    }

    fun hide() {
        mainHandler.post {
            val v = view ?: return@post
            view = null
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(v)
                Log.i(TAG, "lock overlay hidden")
            } catch (e: Exception) {
                Log.w(TAG, "overlay remove failed: ${e.message}")
            }
        }
    }
}
