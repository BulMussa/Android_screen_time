package com.screentime.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Sleep row: bars cut to the day, totals of whole sessions owned by the day they end on, and the tap text. */
class TimelineTest {
    private val day = 86_400L
    private val from = 1_800_000_000L // an arbitrary midnight; the times below are relative to it
    private val to = from + day
    private val h = 3600L

    private fun sleep(start: Long, end: Long, deleted: Boolean = false) =
        Interval("id-$start-$end", "sleep", from + start, from + end, "manual", 0, true, deleted)

    private fun row(vararg rows: Interval) = Timelines.sleep(rows.toList(), from, to)

    @Test
    fun nightAcrossMidnight() {
        val (spans, total) = row(sleep(-2 * h, 6 * h)) // 22:00 yesterday – 06:00 today
        assertEquals(listOf(Span(0, 6 * h, -2 * h, 6 * h)), spans)
        assertEquals(6 * h, Timelines.total(spans)) // the bar still shows only the 6 h of today
        assertEquals(8 * h, total)
        assertEquals("Sleep   22:00 (yesterday) – 06:00   (8h 00m)", Timelines.info("Sleep", spans[0]))
    }

    @Test
    fun shortWakeUpKeepsOneSession() {
        // 23:00 yesterday – 02:00, awake 30 minutes, 02:30 – 06:30
        val (spans, total) = row(sleep(-1 * h, 2 * h), sleep(2 * h + 1800, 6 * h + 1800))
        // The bar is unchanged: two pieces with the gap, both belonging to the same session
        assertEquals(listOf(Span(0, 2 * h, -h, 6 * h + 1800), Span(2 * h + 1800, 6 * h + 1800, -h, 6 * h + 1800)), spans)
        assertEquals(7 * h + 1800, total)
        assertEquals("Sleep   23:00 (yesterday) – 06:30   (7h 30m)", Timelines.info("Sleep", spans[1]))
    }

    @Test
    fun longWakeUpSplitsSessions() {
        val (spans, total) = row(sleep(h, 2 * h), sleep(2 * h + Timelines.SLEEP_JOIN_GAP, 4 * h + Timelines.SLEEP_JOIN_GAP))
        assertEquals(listOf(h, 2 * h + Timelines.SLEEP_JOIN_GAP), spans.map { it.fullStart })
        assertEquals(3 * h, total)
    }

    @Test
    fun napAndNightOnOneDay() {
        val (spans, total) = row(sleep(-2 * h, 6 * h), sleep(14 * h, 15 * h))
        assertEquals(2, spans.size)
        assertEquals(9 * h, total)
        assertEquals("Sleep   14:00 – 15:00   (1h 00m)", Timelines.info("Sleep", spans[1]))
    }

    @Test
    fun noSleep() {
        val (spans, total) = row()
        assertEquals(emptyList<Span>(), spans)
        assertNull(total)
        // Rows only on other days, or deleted, don't count either
        assertNull(row(sleep(-30 * h, -20 * h), sleep(2 * h, 3 * h, deleted = true)).second)
    }

    @Test
    fun sessionEndingTomorrow() {
        // Last night 22:00 – 06:00 and tonight from 22:00 until 06:00 tomorrow
        val (spans, total) = row(sleep(-2 * h, 6 * h), sleep(22 * h, day + 6 * h))
        assertEquals(Span(22 * h, day, 22 * h, day + 6 * h), spans[1])
        assertEquals(8 * h, total) // tonight's piece belongs to tomorrow
        assertEquals("Sleep   22:00 – 06:00 (tomorrow)   (8h 00m)", Timelines.info("Sleep", spans[1]))
        // Only tonight: shown on the right, but no total
        val (alone, none) = row(sleep(22 * h, day + 6 * h))
        assertEquals(listOf(Span(22 * h, day, 22 * h, day + 6 * h)), alone)
        assertNull(none)
    }

    @Test
    fun sessionEndingAtMidnightBelongsToTheDay() {
        assertEquals(2 * h, row(sleep(22 * h, day)).second)
    }

    @Test
    fun otherRowsKeepTheirText() {
        assertEquals("Phone · Active   07:30 – 08:15   (0h 45m)", Timelines.info("Phone · Active", Span(27_000, 29_700)))
    }
}
