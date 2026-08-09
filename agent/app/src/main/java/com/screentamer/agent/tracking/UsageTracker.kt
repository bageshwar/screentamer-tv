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

        /**
         * Pure delta computation (unit-tested): the per-package foreground ms
         * accumulated since the previous snapshot, given the previous snapshot's
         * date + totals. A null previous baseline (first call after restart)
         * yields no delta. When the date changed (midnight rollover) the whole
         * of the current day's usage counts, because the baseline belongs to
         * yesterday. Negative deltas (stats reset, uninstalls) are dropped.
         */
        fun deltaSince(prevDate: String?, prevTotals: Map<String, Long>?, date: String, totals: Map<String, Long>): Map<String, Long>? {
            if (prevDate == null || prevTotals == null) return null
            val delta = mutableMapOf<String, Long>()
            if (prevDate != date) {
                for ((pkg, ms) in totals) if (ms > 0) delta[pkg] = ms
            } else {
                for ((pkg, ms) in totals) {
                    val d = ms - (prevTotals[pkg] ?: 0L)
                    if (d > 0) delta[pkg] = d
                }
            }
            return delta.takeIf { it.isNotEmpty() }
        }
    }

    /**
     * Cumulative per-package foreground ms since the previous [snapshot] call,
     * baselined on the first call after boot (see [snapshot]).
     */
    private var lastTotals: Map<String, Long>? = null

    /** Date ([snapshot] day) the [lastTotals] baseline belongs to. */
    private var lastSnapshotDate: String? = null

    /** Millis since midnight that each app has been in the foreground today. */
    fun usageToday(now: Date = Date()): Map<String, Long> {
        return try {
            val usm = context.getSystemService(UsageStatsManager::class.java)
            val cal = Calendar.getInstance().apply { time = now }
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val dayStart = cal.timeInMillis

            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, dayStart, now.time)
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
     * One snapshot of usage: the day's per-package totals, the hour the
     * snapshot was taken, and the per-package foreground delta since the
     * previous call (null when there is no baseline yet, or nothing new).
     */
    data class Snapshot(
        val date: String,
        val hour: Int,
        val totals: Map<String, Long>,
        val delta: Map<String, Long>?
    )

    /**
     * Snapshot of today's usage plus the per-package foreground delta since
     * the previous call. The first call after a service restart returns a null
     * delta (baseline only) so a restart never dumps the whole day into the
     * current hour bucket. If the date changed since the last call (midnight
     * rollover), the whole of today's usage counts as delta — the previous
     * baseline belonged to yesterday. Negative deltas (stats reset, uninstalls)
     * are dropped.
     */
    fun snapshot(): Snapshot {
        val now = Date()
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
        val hour = Calendar.getInstance().apply { time = now }.get(Calendar.HOUR_OF_DAY)
        val totals = usageToday(now)
        val delta = deltaSince(lastSnapshotDate, lastTotals, date, totals)
        lastTotals = totals
        lastSnapshotDate = date
        return Snapshot(date, hour, totals, delta)
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
