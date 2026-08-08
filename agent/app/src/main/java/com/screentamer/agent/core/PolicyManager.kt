package com.screentamer.agent.core

import org.json.JSONObject
import java.util.Calendar

/**
 * Holds the enforcement policy received from the parent server and decides
 * when to lock the screen or kill a blacklisted app.
 *
 * Policy shape:
 * {
 *   "dailyLimitMs": 7200000,          // 0 = unlimited
 *   "curfew": { "enabled": false, "start": "20:00", "end": "06:00" },
 *   "blacklist": ["com.netflix.ninja"],
 *   "lockdown": false                  // parent-requested instant lock
 * }
 */
class PolicyManager {

    @Volatile
    var policy: JSONObject = defaultPolicy()
        private set

    fun apply(p: JSONObject) {
        policy = p
    }

    fun defaultPolicy(): JSONObject = JSONObject()
        .put("dailyLimitMs", 0)
        .put("curfew", JSONObject()
            .put("enabled", false)
            .put("start", "20:00")
            .put("end", "06:00"))
        .put("blacklist", org.json.JSONArray())
        .put("lockdown", false)

    fun isLockedDown(): Boolean = policy.optBoolean("lockdown", false)

    fun shouldLock(now: Calendar, totalTodayMs: Long): Boolean {
        if (isLockedDown()) return true
        val curfew = policy.optJSONObject("curfew") ?: return false
        if (curfew.optBoolean("enabled", false)) {
            val inRange = curfewInRange(now, curfew.optString("start", "20:00"), curfew.optString("end", "06:00"))
            if (inRange) return true
        }
        val limit = policy.optLong("dailyLimitMs", 0)
        if (limit > 0 && totalTodayMs >= limit) return true
        return false
    }

    fun isBlacklisted(pkg: String): Boolean {
        val list = policy.optJSONArray("blacklist") ?: return false
        for (i in 0 until list.length()) {
            if (list.optString(i) == pkg) return true
        }
        return false
    }

    private fun curfewInRange(now: Calendar, start: String, end: String): Boolean {
        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMin = parseMinutes(start)
        val endMin = parseMinutes(end)
        return if (endMin > startMin) {
            nowMin >= startMin && nowMin < endMin
        } else {
            nowMin >= startMin || nowMin < endMin // wraps past midnight
        }
    }

    private fun parseMinutes(hhmm: String): Int {
        val parts = hhmm.split(":")
        if (parts.size != 2) return 0
        return (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
    }
}
