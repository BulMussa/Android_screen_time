package com.screentime.mobile

import androidx.compose.ui.graphics.Color
import java.time.LocalDate

/** [start] and [end] are seconds after the phone's local midnight of the day shown. */
data class Span(val start: Long, val end: Long)

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

    fun rows(day: LocalDate): List<TimelineRow> {
        val on = spans(listOf("pc_on"), day) // PC on, idle time included, like the Windows app's laptop row
        val phone = spans(listOf("phone_active"), day)
        val sleep = spans(listOf("sleep"), day)
        return listOf(
            TimelineRow("Laptop", if (on.isEmpty()) "" else "${short(total(on))} on", listOf(TimelinePart("Laptop · PC on", COLOR_ON, on))),
            TimelineRow("Phone", if (phone.isEmpty()) "" else short(total(phone)), listOf(TimelinePart("Phone · Active", COLOR_PHONE, phone))),
            TimelineRow("Sleep", if (sleep.isEmpty()) "" else short(total(sleep)), listOf(TimelinePart("Sleep", COLOR_SLEEP, sleep))),
        )
    }
}
