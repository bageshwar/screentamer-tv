package com.screentamer.agent.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for [UsageTracker.deltaSince] (the pure, testable half of snapshot()). */
class UsageTrackerTest {

    @Test
    fun `first snapshot has no baseline`() {
        assertNull(UsageTracker.deltaSince(null, null, "2026-08-09", mapOf("com.netflix.ninja" to 1_200_000L)))
    }

    @Test
    fun `same-day delta is the growth since the previous snapshot`() {
        val d = UsageTracker.deltaSince(
            "2026-08-09", mapOf("com.netflix.ninja" to 1_200_000L),
            "2026-08-09", mapOf("com.netflix.ninja" to 1_500_000L),
        )
        assertEquals(mapOf("com.netflix.ninja" to 300_000L), d)
    }

    @Test
    fun `new app on the same day counts fully`() {
        val d = UsageTracker.deltaSince(
            "2026-08-09", mapOf("com.netflix.ninja" to 1_200_000L),
            "2026-08-09", mapOf("com.netflix.ninja" to 1_200_000L, "com.google.android.youtube.tv" to 900_000L),
        )
        assertEquals(mapOf("com.google.android.youtube.tv" to 900_000L), d)
    }

    @Test
    fun `midnight rollover with a shared package counts the whole day`() {
        // YouTube ran before AND after midnight; the pre-midnight baseline must
        // not be subtracted from the new day's totals.
        val d = UsageTracker.deltaSince(
            "2026-08-08", mapOf("com.google.android.youtube.tv" to 5_000_000L),
            "2026-08-09", mapOf("com.google.android.youtube.tv" to 1_200_000L, "com.netflix.ninja" to 600_000L),
        )
        assertEquals(mapOf("com.google.android.youtube.tv" to 1_200_000L, "com.netflix.ninja" to 600_000L), d)
    }

    @Test
    fun `negative deltas from stats resets are dropped`() {
        val d = UsageTracker.deltaSince(
            "2026-08-09", mapOf("com.netflix.ninja" to 5_000_000L),
            "2026-08-09", mapOf("com.netflix.ninja" to 0L),
        )
        assertNull(d)
    }
}
