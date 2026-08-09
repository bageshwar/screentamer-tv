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

    /**
     * Cumulative per-package foreground ms since the previous [snapshot] call,
     * baselined on the first call after boot (see [snapshot]).
     */
    private var lastTotals: Map<String, Long>? = null

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
     * Snapshot of today's usage plus the per-package foreground delta since
     * the previous call. The first call after a service restart returns a null
     * delta (baseline only) so a restart never dumps the whole day into the
     * current hour bucket. Negative deltas (stats reset, uninstalls) are
     * dropped.
     */
    fun snapshot(): Pair<Map<String, Long>, Map<String, Long>?> {
        val totals = usageToday()
        val last = lastTotals
        lastTotals = totals
        if (last == null) return totals to null
        val delta = mutableMapOf<String, Long>()
        for ((pkg, ms) in totals) {
            val d = ms - (last[pkg] ?: 0L)
            if (d > 0) delta[pkg] = d
        }
        return totals to delta.takeIf { it.isNotEmpty() }
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
