package com.screentime.mobile

import kotlin.math.roundToInt

/** One app's whole time on a day, and its share of all apps' time that day (0..1). */
data class AppTime(val app: String, val seconds: Long, val share: Double)

/** The phone's apps of one day for the home screen: the top 3 and the full list, like the Windows Report tab. */
object AppTimes {
    /**
     * Every app with at least a second, most used first (equal times by name), one entry per app. The [hidden]
     * apps (the launchers) are left out, also of the shares.
     */
    fun ranked(rows: List<Usage>, hidden: Set<String> = emptySet()): List<AppTime> {
        val totals = HashMap<String, Long>()
        for (r in rows) if (r.seconds > 0 && r.app !in hidden) totals.merge(r.app, r.seconds) { a, b -> a + b }
        val whole = totals.values.sum().coerceAtLeast(1)
        return totals.entries
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key.lowercase() })
            .map { AppTime(it.key, it.value, it.value.toDouble() / whole) }
    }

    /** Like [short], but leaves out the hours until there is at least one (the Windows app's "duration"). */
    fun duration(seconds: Long): String {
        val minutes = seconds / 60
        return if (minutes >= 60) short(seconds) else "${minutes}m"
    }

    /** 42% */
    fun percent(share: Double): String = "${(share * 100).roundToInt()}%"
}
