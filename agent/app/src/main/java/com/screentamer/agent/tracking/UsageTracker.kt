package com.screentamer.agent.tracking

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.SystemClock
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
        private const val INSTALLED_CACHE_MS = 5 * 60 * 1000L

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

    /** Cached set of currently-installed package names (see [installedPackages]). */
    private var installedCache: Set<String>? = null
    private var installedCacheAt = 0L

    /** True once an enumeration of installed packages has actually succeeded. */
    private var installedKnown = false

    /**
     * Packages actually installed on the device, cached for 5 minutes.
     *
     * Fire OS keeps returning phantom UsageStats entries — including a full
     * day of "foreground" time — for apps that were uninstalled (e.g. the
     * long-defunct Sling Vue, HBO Now and old Prime Video packages on this TV
     * report exactly 86_399_999 ms/day, which blows the daily total past 96h
     * for a single calendar day). Only packages that still exist may count.
     */
    fun installedPackages(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        installedCache?.let {
            if (now - installedCacheAt < INSTALLED_CACHE_MS) return it
        }
        return try {
            val set = context.packageManager.getInstalledPackages(0)
                .map { it.packageName }
                .toSet()
            installedCache = set
            installedCacheAt = now
            installedKnown = true
            set
        } catch (e: Exception) {
            Log.w(TAG, "could not enumerate installed packages: ${e.message}")
            installedCache ?: emptySet()
        }
    }

    /** Result of a usage query: per-package totals plus whether the query is authoritative. */
    data class UsageResult(val totals: Map<String, Long>, val authoritative: Boolean)

    /** Millis since midnight that each app has been in the foreground today. */
    fun usageToday(now: Date = Date()): UsageResult {
        return try {
            val usm = context.getSystemService(UsageStatsManager::class.java)
            val cal = Calendar.getInstance().apply { time = now }
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val dayStart = cal.timeInMillis

            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, dayStart, now.time)
            val installed = installedPackages()
            val tracked = stats
                .filter {
                    it.totalTimeInForeground > 0 &&
                        !KnownApps.isSystemish(it.packageName) &&
                        it.packageName in installed
                }
                .associate { it.packageName to it.totalTimeInForeground }
            Log.d(TAG, "usage today: ${tracked.size} apps, ${tracked.values.sum()}ms (of ${stats.size} stats entries)")
            // Only authoritative if the query returned stats AND the installed
            // package list is known; otherwise we cannot tell real absence from
            // an unavailable observation, so callers must not prune on it.
            UsageResult(tracked, authoritative = installedKnown)
        } catch (e: SecurityException) {
            // PACKAGE_USAGE_STATS not granted via adb yet.
            Log.w(TAG, "PACKAGE_USAGE_STATS not granted — usage tracking off")
            UsageResult(emptyMap(), authoritative = false)
        }
    }

    /**
     * One snapshot of usage: the day's per-package totals, the hour the
     * snapshot was taken, and the per-package foreground delta since the
     * previous call (null when there is no baseline yet, or nothing new).
     * ```
     * authoritative is false when the underlying usage query was unavailable
     * (e.g. PACKAGE_USAGE_STATS not granted), so callers know the totals are
     * empty rather than genuinely zero and must not treat that as a wipe.
     */
    data class Snapshot(
        val date: String,
        val hour: Int,
        val totals: Map<String, Long>,
        val delta: Map<String, Long>?,
        val authoritative: Boolean
    )

    /**
     * Snapshot of today's usage plus the per-package foreground delta since
     * the previous call. The first call after a service restart returns a null
     * delta (baseline only) so a restart never dumps the whole day into the
     * current hour bucket. If the date changed since the last call (midnight
     * rollover), the whole of today's usage counts as delta — the previous
     * baseline belonged to yesterday. Negative deltas (stats reset, uninstalls)
     * are dropped. A non-authoritative observation (usage query unavailable)
     * returns empty totals so callers neither replace the baseline with a bogus
     * map nor write stale data into the day file.
     */
    fun snapshot(): Snapshot {
        val now = Date()
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
        val hour = Calendar.getInstance().apply { time = now }.get(Calendar.HOUR_OF_DAY)
        val result = usageToday(now)
        if (!result.authoritative) {
            return Snapshot(date, hour, emptyMap(), null, authoritative = false)
        }
        val delta = deltaSince(lastSnapshotDate, lastTotals, date, result.totals)
        lastTotals = result.totals
        lastSnapshotDate = date
        return Snapshot(date, hour, result.totals, delta, authoritative = true)
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
            last?.takeIf { !KnownApps.isSystemish(it) && it in installedPackages() }
        } catch (e: SecurityException) {
            Log.w(TAG, "PACKAGE_USAGE_STATS not granted — cannot detect foreground app")
            null
        }
    }
}
