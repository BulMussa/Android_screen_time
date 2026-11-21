package com.screentime.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** The home screen's "Last synced at" line and the problem shown under it. */
class LastSyncedTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val today = LocalDate.of(2026, 11, 21)
    private fun ms(day: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 11, day, h, m, 0, 0, zone).toInstant().toEpochMilli()

    private val ok = Outcome("server", 0, null)
    private val failed = Outcome("direct", 0, LaptopNotFoundException())
    private val quiet = Outcome("direct", 0, LaptopNotFoundException(), quiet = true)

    @Test
    fun timeToday() = assertEquals("Last synced at 14:29", SyncStatus.lastSynced(ms(21, 14, 29), zone, today))

    @Test
    fun dateOnAnotherDay() = assertEquals("Last synced at 20 Nov, 23:58", SyncStatus.lastSynced(ms(20, 23, 58), zone, today))

    @Test
    fun never() = assertEquals("Not synced yet", SyncStatus.lastSynced(0, zone, today))

    @Test
    fun syncedWhenAnyTransportWorked() {
        assertTrue(SyncStatus.synced(listOf(failed, ok)))
        assertFalse(SyncStatus.synced(listOf(failed)))
        assertFalse(SyncStatus.synced(emptyList()))
    }

    @Test
    fun problemOnlyWhenEverythingReportedFailed() {
        assertNull(SyncStatus.failure(listOf(failed, ok), "status", "old"))
        assertEquals("status", SyncStatus.failure(listOf(failed), "status", null))
        // A quiet background miss changes nothing
        assertEquals("old", SyncStatus.failure(listOf(quiet), "status", "old"))
        assertNull(SyncStatus.failure(listOf(quiet), "status", null))
    }
}
