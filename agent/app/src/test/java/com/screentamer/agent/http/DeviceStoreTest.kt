package com.screentamer.agent.http

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Unit tests for [DeviceStore] history persistence and pruning. */
class DeviceStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = DeviceStore(tmp.newFolder())

    private fun today(): String {
        val cal = Calendar.getInstance()
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return "${cal.get(Calendar.YEAR)}-${if (m < 10) "0$m" else m}-${if (d < 10) "0$d" else d}"
    }

    private fun yesterday(): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_MONTH, -1)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return "${cal.get(Calendar.YEAR)}-${if (m < 10) "0$m" else m}-${if (d < 10) "0$d" else d}"
    }

    @Test
    fun `recordUsage writes and reads back per-package totals`() {
        val s = store()
        s.recordUsage(
            today(),
            mapOf("com.netflix.ninja" to 1_200_000L, "com.google.android.youtube.tv" to 600_000L),
            mapOf("19" to mapOf("com.netflix.ninja" to 300_000L))
        )
        assertEquals(1_200_000L, s.usageFor(today()).optLong("com.netflix.ninja"))
        assertEquals(300_000L, s.loadHourly(today())["19"]?.get("com.netflix.ninja"))
    }

    @Test
    fun `non-authoritative observation keeps existing totals`() {
        val s = store()
        s.recordUsage(today(), mapOf("com.netflix.ninja" to 1_200_000L)) // authoritative
        // A failed observation (empty, non-authoritative) must not wipe the day.
        s.recordUsage(today(), emptyMap(), null, authoritative = false)
        assertEquals(1_200_000L, s.usageFor(today()).optLong("com.netflix.ninja"))
    }

    @Test
    fun `authoritative snapshot drops packages not reported anymore`() {
        val s = store()
        s.recordUsage(today(), mapOf("com.netflix.ninja" to 1_200_000L, "com.sling" to 86_399_999L))
        // Netflix still watched, Sling gone from stats: stale top-level dropped.
        s.recordUsage(today(), mapOf("com.netflix.ninja" to 1_500_000L))
        val day = s.usageFor(today())
        assertTrue(day.has("com.netflix.ninja"))
        assertFalse(day.has("com.sling"))
        assertEquals(1_500_000L, day.optLong("com.netflix.ninja"))
    }

    @Test
    fun `pruneUninstalled removes entries for uninstalled packages`() {
        val s = store()
        s.recordUsage(
            yesterday(),
            mapOf("com.netflix.ninja" to 1_200_000L, "com.sling" to 86_399_999L),
            mapOf("20" to mapOf("com.netflix.ninja" to 900_000L, "com.sling" to 86_399_999L))
        )
        s.recordUsage(today(), mapOf("com.netflix.ninja" to 600_000L, "com.sling" to 86_399_999L))

        val touched = s.pruneUninstalled(setOf("com.netflix.ninja", "com.screentamer.agent"))

        assertEquals(2, touched)
        assertFalse(s.usageFor(yesterday()).has("com.sling"))
        assertFalse(s.usageFor(today()).has("com.sling"))
        // Hourly entries for the uninstalled package are gone too.
        assertFalse(s.loadHourly(yesterday())["20"]?.containsKey("com.sling") ?: false)
        assertTrue(s.usageFor(today()).has("com.netflix.ninja"))
    }

    @Test
    fun `pruneUninstalled with empty installed set does nothing`() {
        val s = store()
        s.recordUsage(today(), mapOf("com.netflix.ninja" to 600_000L))
        assertEquals(0, s.pruneUninstalled(emptySet()))
        assertEquals(600_000L, s.usageFor(today()).optLong("com.netflix.ninja"))
    }

    @Test
    fun `historyFor builds 14 day rows ending today`() {
        val s = store()
        s.recordUsage(yesterday(), mapOf("com.netflix.ninja" to 1_200_000L))
        val arr = s.historyFor(14)
        assertEquals(14, arr.length())
        // The oldest row is 13 days back, newest is today.
        assertEquals(today(), arr.getJSONObject(13).getString("date"))
        // Yesterday's row carries the recorded usage.
        val row = arr.getJSONObject(12)
        assertEquals(1_200_000L, row.getLong("totalMs"))
        assertTrue(row.getJSONObject("apps").has("com.netflix.ninja"))
    }
}