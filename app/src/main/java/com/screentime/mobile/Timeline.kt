package com.screentime.mobile

import androidx.compose.ui.graphics.Color
import java.time.LocalDate

/** [start] and [end] are seconds after the phone's local midnight of the day shown. */
data class Span(val start: Long, val end: Long)

data class TimelinePart(val title: String, val color: Color, val spans: List<Span>)

data class TimelineRow(val name: String, val summary: String, val parts: List<TimelinePart>)

val COLOR_ON_LIGHT = Color(0xFF93C5FD)
val COLOR_ON = Color(0xFF2563EB)
val COLOR_PHONE = Color(0xFFF97316)
val COLOR_SLEEP = Color(0xFF7C3AED)

/** Laptop, phone and sleep rows for one day, built from the local database. */
object Timelines {
    private const val DAY = 86_400L

    fun spans(kinds: List<String>, day: LocalDate): List<Span> {
        val from = startOfDay(day)
        val to = startOfDay(day.plusDays(1))
        val cut = Graph.db.overlapping(kinds, from, to)
            .map { Span(maxOf(it.start, from) - from, minOf(minOf(it.end, to) - from, DAY)) }
            .filter { it.end > it.start }
        return merge(cut)
    }

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
        val on = spans(listOf("pc_on"), day)
        val active = spans(listOf("pc_active"), day)
        val phone = spans(listOf("phone_active"), day)
        val sleep = spans(listOf("sleep"), day)
        val laptop = when {
            active.isNotEmpty() -> listOf(
                TimelinePart("Laptop · PC on", COLOR_ON_LIGHT, on),
                TimelinePart("Laptop · Active", COLOR_ON, active),
            )
            else -> listOf(TimelinePart("Laptop · PC on", COLOR_ON, on))
        }
        val laptopSummary = when {
            active.isNotEmpty() -> "${short(total(active))} active"
            on.isNotEmpty() -> "${short(total(on))} on"
            else -> ""
        }
        return listOf(
            TimelineRow("Laptop", laptopSummary, laptop),
            TimelineRow("Phone", if (phone.isEmpty()) "" else short(total(phone)), listOf(TimelinePart("Phone · Active", COLOR_PHONE, phone))),
            TimelineRow("Sleep", if (sleep.isEmpty()) "" else short(total(sleep)), listOf(TimelinePart("Sleep", COLOR_SLEEP, sleep))),
        )
    }
}
