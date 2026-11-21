package com.screentime.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppTimesTest {
    private fun u(app: String, seconds: Long) = Usage("2026-11-21", app, seconds, 1)

    @Test
    fun mostUsedFirstWithShares() {
        val ranked = AppTimes.ranked(listOf(u("Chrome", 600), u("YouTube", 3000), u("Maps", 1400)))
        assertEquals(listOf("YouTube", "Maps", "Chrome"), ranked.map { it.app })
        assertEquals(0.6, ranked[0].share, 1e-9)
        assertEquals(listOf("60%", "28%", "12%"), ranked.map { AppTimes.percent(it.share) })
    }

    @Test
    fun sameNameIsOneEntryAndEmptyRowsAreLeftOut() {
        val ranked = AppTimes.ranked(listOf(u("Phone", 100), u("Phone", 50), u("Clock", 0)))
        assertEquals(listOf(AppTime("Phone", 150, 1.0)), ranked)
    }

    @Test
    fun equalTimesByName() {
        assertEquals(listOf("alpha", "Beta"), AppTimes.ranked(listOf(u("Beta", 60), u("alpha", 60))).map { it.app })
    }

    @Test
    fun launcherIsLeftOutAlsoOfTheShares() {
        val ranked = AppTimes.ranked(listOf(u("Chrome", 300), u("System launcher", 900), u("Maps", 100)), setOf("System launcher"))
        assertEquals(listOf("Chrome", "Maps"), ranked.map { it.app })
        assertEquals(0.75, ranked[0].share, 1e-9)
    }

    @Test
    fun noRows() {
        assertTrue(AppTimes.ranked(emptyList()).isEmpty())
    }

    @Test
    fun durationLeavesOutHoursUntilThereIsOne() {
        assertEquals("0m", AppTimes.duration(59))
        assertEquals("59m", AppTimes.duration(3599))
        assertEquals("1h 00m", AppTimes.duration(3600))
        assertEquals("2h 05m", AppTimes.duration(2 * 3600 + 5 * 60 + 30))
    }
}
