package com.screentamer.agent.tracking

import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import com.screentamer.agent.data.KnownApps
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Tracks per-package foreground usage for the current day via UsageStatsManager
 * and detects the active app from UsageStats events.
 */
class UsageTracker(private val context: Context) {

    companion object {
        private const val TAG = "ScreenTamer/UsageTracker"

        fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    /** Millis since midnight that each app has been in the foreground today. */
    fun usageToday(): Map<String, Long> {
        return try {
            val usm = context.getSystemService(UsageStatsManager::class.java)
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val dayStart = cal.timeInMillis

            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, dayStart, System.currentTimeMillis())
            val tracked = stats
                .filter { it.totalTimeInForeground > 0 && !KnownApps.isSystemish(it.packageName) }
                .associate { it.packageName to it.totalTimeInForeground }
            Log.d(TAG, "usage today: ${tracked.size} apps, ${tracked.values.sum()}ms")
            tracked
        } catch (e: SecurityException) {
            // PACKAGE_USAGE_STATS not granted via adb yet.
            Log.w(TAG, "PACKAGE_USAGE_STATS not granted — usage tracking off")
            emptyMap()
        }
    }

    /**
     * Best-effort current foreground app, derived from UsageStats events
     * (last MOVE_TO_FOREGROUND). Avoids the removed ActivityManager.runningTasks.
     */
    fun foregroundApp(): String? {
        return try {
            val usm = context.getSystemService(UsageStatsManager::class.java)
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 2 * 60 * 60 * 1000L, now)
            val event = android.app.usage.UsageEvents.Event()
            var last: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    last = event.packageName
                }
            }
            last?.takeIf { !KnownApps.isSystemish(it) }
        } catch (e: SecurityException) {
            Log.w(TAG, "PACKAGE_USAGE_STATS not granted — cannot detect foreground app")
            null
        }
    }
}
