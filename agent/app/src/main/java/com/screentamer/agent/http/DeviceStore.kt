package com.screentamer.agent.http

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * On-device persistence: per-day usage history files, an activity log and a
 * health record (service starts, tick failures, last tick time) so a parent
 * can see — from the dashboard — whether the agent is actually running.
 */
class DeviceStore(private val dataDir: File) {

    companion object {
        private const val TAG = "ScreenTamer/DeviceStore"
    }

    private val historyDir = File(dataDir, "history")
    private val healthFile = File(dataDir, "health.json")
    private val logFile = File(dataDir, "log.json")

    init {
        historyDir.mkdirs()
        dataDir.mkdirs()
        Log.i(TAG, "store ready at ${dataDir.path}")
    }

    // ------------------------------------------------------------------
    // History (data/history/<yyyy-mm-dd>.json, atomic writes)
    // ------------------------------------------------------------------

    private fun dayFile(date: String): File = File(historyDir, "$date.json")

    private fun readDay(date: String): JSONObject {
        val f = dayFile(date)
        if (!f.exists()) return JSONObject()
        return try {
            JSONObject(f.readText())
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun writeDay(date: String, bucket: JSONObject) {
        val f = dayFile(date)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(bucket.toString())
        tmp.renameTo(f)
    }

    fun recordUsage(date: String, apps: Map<String, Long>): JSONObject {
        val bucket = readDay(date)
        for ((pkg, ms) in apps) {
            if (ms >= 0 && bucket.optLong(pkg) != ms) bucket.put(pkg, ms)
        }
        writeDay(date, bucket)
        Log.d(TAG, "saved usage $date apps=${apps.size}")
        return bucket
    }

    fun usageFor(date: String): JSONObject = readDay(date)

    fun historyFor(days: Int): JSONArray {
        val out = JSONArray()
        for (i in days - 1 downTo 0) {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_MONTH, -i)
            val key = dateKey(cal)
            val apps = readDay(key)
            val total = if (apps.length() == 0) 0L else apps.keys().asSequence().sumOf { apps.optLong(it as String) }
            out.put(JSONObject().put("date", key).put("totalMs", total).put("apps", apps))
        }
        return out
    }

    fun sweep(retentionDays: Int = 90) {
        val cutoff = System.currentTimeMillis() - retentionDays * 24L * 60 * 60 * 1000
        var deleted = 0
        historyDir.listFiles()?.forEach { f ->
            if (f.isFile && f.lastModified() < cutoff) {
                f.delete()
                deleted++
            }
        }
        if (deleted > 0) Log.i(TAG, "swept $deleted history files older than ${retentionDays}d")
    }

    // ------------------------------------------------------------------
    // Activity log (persisted, capped)
    // ------------------------------------------------------------------

    fun appendLog(msg: String) {
        val arr = readLog()
        arr.put(JSONObject().put("ts", System.currentTimeMillis()).put("msg", msg))
        while (arr.length() > 200) arr.remove(0)
        logFile.writeText(arr.toString())
    }

    fun readLog(): JSONArray {
        if (!logFile.exists()) return JSONArray()
        return try {
            JSONArray(logFile.readText())
        } catch (e: Exception) {
            JSONArray()
        }
    }

    // ------------------------------------------------------------------
    // Health & observability
    // ------------------------------------------------------------------

    fun health(): JSONObject {
        if (!healthFile.exists()) return JSONObject()
        return try {
            JSONObject(healthFile.readText())
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun saveHealth(h: JSONObject) {
        healthFile.writeText(h.toString())
    }

    fun bumpServiceStart() {
        val h = health()
        h.put("startCount", h.optInt("startCount") + 1)
        h.put("lastStartAt", System.currentTimeMillis())
        saveHealth(h)
        Log.i(TAG, "service start bumped to #${h.optInt("startCount")}")
    }

    fun noteTick() {
        val h = health()
        h.put("lastTickAt", System.currentTimeMillis())
        saveHealth(h)
    }

    fun noteTickFailure(e: Throwable) {
        val h = health()
        h.put("tickFailures", h.optInt("tickFailures") + 1)
        h.put("lastError", JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("msg", e.message ?: e.javaClass.simpleName)
            .put("trace", e.stackTrace.take(4).joinToString(" | ")))
        saveHealth(h)
        Log.w(TAG, "tick failure #${h.optInt("tickFailures")}: ${e.message}")
    }

    private fun dateKey(cal: Calendar): String {
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return "${cal.get(Calendar.YEAR)}-${if (m < 10) "0$m" else m}-${if (d < 10) "0$d" else d}"
    }
}
