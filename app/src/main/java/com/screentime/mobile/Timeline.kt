package com.screentime.mobile

import androidx.compose.ui.graphics.Color
import java.time.LocalDate

/**
 * [start] and [end] are seconds after the phone's local midnight of the day shown. A sleep span also has
 * [fullStart] and [fullEnd], its whole session on the same scale (may be negative or past 86 400).
 */
data class Span(val start: Long, val end: Long, val fullStart: Long? = null, val fullEnd: Long? = null)

data class TimelinePart(val title: String, val color: Color, val spans: List<Span>)

data class TimelineRow(val name: String, val summary: String, val parts: List<TimelinePart>)

val COLOR_ON = Color(0xFF2563EB)
val COLOR_PHONE = Color(0xFFF97316)
val COLOR_SLEEP = Color(0xFF7C3AED)
val COLOR_NOW = Color(0xFF4B5563) // the now-line, as in the Windows app
val COLOR_NOW_DARK = Color(0xFF9CA3AF) // the same gray, lighter so it shows on a dark background

/** Laptop, phone and sleep rows for one day, built from the local database. */
object Timelines {
    private const val DAY = 86_400L

    /** Sleep periods less than this apart (seconds) are one session, so a short wake-up doesn't split a night. */
    const val SLEEP_JOIN_GAP = 90 * 60L

    /** How far before and after the day sleep rows are read, to find a session that started the day before. */
    private const val SLEEP_REACH = 12 * 3600L

    fun spans(kinds: List<String>, day: LocalDate, db: Db = Graph.db): List<Span> {
        val from = startOfDay(day)
        val to = startOfDay(day.plusDays(1))
        return cut(db.overlapping(kinds, from, to), from, to)
    }

    /**
     * The rows of one day [from, to) (seconds) as merged spans after that midnight. Only kind and times count,
     * so a row looks the same whichever route (server or laptop) brought it.
     */
    fun cut(rows: List<Interval>, from: Long, to: Long): List<Span> = merge(
        rows.filter { !it.deleted }
            .map { Span(maxOf(it.start, from) - from, minOf(minOf(it.end, to) - from, DAY)) }
            .filter { it.end > it.start },
    )

    fun merge(spans: List<Span>): List<Span> {
        val merged = ArrayList<Span>()
        for (s in spans.sortedBy { it.start }) {
            val last = merged.lastOrNull()
            if (last != null && s.start <= last.end) merged[merged.size - 1] = Span(last.start, maxOf(last.end, s.end)) else merged.add(s)
        }
        return merged
    }

    fun total(spans: List<Span>): Long = spans.sumOf { it.end - it.start }

    /** Whole sleep sessions as absolute [start, end] pairs: the rows merged, with gaps under [SLEEP_JOIN_GAP] joined. */
    fun sleepSessions(rows: List<Interval>): List<LongArray> {
        val sessions = ArrayList<LongArray>()
        for (r in rows.filter { !it.deleted && it.end > it.start }.sortedBy { it.start }) {
            val last = sessions.lastOrNull()
            if (last != null && r.start - last[1] < SLEEP_JOIN_GAP) last[1] = maxOf(last[1], r.end)
            else sessions.add(longArrayOf(r.start, r.end))
        }
        return sessions
    }

    /**
     * The Sleep row of the day [from, to): the bar's spans, cut to the day exactly like [cut], each carrying its
     * whole session; and the total of the sessions that end on this day (a night belongs to the day it ends on),
     * or null if none does.
     */
    fun sleep(rows: List<Interval>, from: Long, to: Long): Pair<List<Span>, Long?> {
        val sessions = sleepSessions(rows)
        val spans = cut(rows, from, to).map { s ->
            val session = sessions.firstOrNull { s.start + from >= it[0] && s.start + from < it[1] }
            if (session == null) s else s.copy(fullStart = session[0] - from, fullEnd = session[1] - from)
        }
        val owned = sessions.filter { it[1] > from && it[1] <= to }
        return spans to (if (owned.isEmpty()) null else owned.sumOf { it[1] - it[0] })
    }

    /** The tap text of a span: its own times, or for sleep the whole session's, marked when on another day. */
    fun info(title: String, span: Span): String {
        val start = span.fullStart ?: span.start
        val end = span.fullEnd ?: span.end
        val from = if (start < 0) "${hm(Math.floorMod(start, DAY))} (yesterday)" else hm(start)
        val until = if (end > DAY) "${hm(end - DAY)} (tomorrow)" else hm(end)
        return "$title   $from – $until   (${short(end - start)})"
    }

    fun rows(day: LocalDate): List<TimelineRow> {
        val on = spans(listOf("pc_on"), day) // PC on, idle time included, like the Windows app's laptop row
        val phone = spans(listOf("phone_active"), day)
        val from = startOfDay(day)
        val to = startOfDay(day.plusDays(1))
        val (sleep, slept) = sleep(Graph.db.overlapping(listOf("sleep"), from - SLEEP_REACH, to + SLEEP_REACH), from, to)
        return listOf(
            TimelineRow("Laptop", if (on.isEmpty()) "" else "${short(total(on))} on", listOf(TimelinePart("Laptop · PC on", COLOR_ON, on))),
            TimelineRow("Phone", if (phone.isEmpty()) "" else short(total(phone)), listOf(TimelinePart("Phone · Active", COLOR_PHONE, phone))),
            TimelineRow("Sleep", if (slept == null) "" else short(slept), listOf(TimelinePart("Sleep", COLOR_SLEEP, sleep))),
        )
    }
}
