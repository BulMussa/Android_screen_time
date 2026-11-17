package com.screentime.mobile

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Phone use from Android's usage statistics: screen-on periods and time per app. */
object UsageCollector {
    // Event types; written as numbers because some constants need a newer Android than minSdk.
    private const val ACTIVITY_RESUMED = 1
    private const val ACTIVITY_PAUSED = 2
    private const val SCREEN_INTERACTIVE = 15
    private const val SCREEN_NON_INTERACTIVE = 16

    private const val JOIN_GAP = 60L // screen-on periods closer than this (seconds) become one
    private const val MIN_SPAN = 3L // shorter ones (a notification lighting the screen) are dropped

    fun hasAccess(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Reads yesterday's and today's events and updates the phone's rows; safe to run again and again. */
    fun scan(context: Context) {
        if (!hasAccess(context)) return
        val zone = ZoneId.systemDefault()
        val fromSec = startOfDay(LocalDate.now(zone).minusDays(1)) // a fixed start, so interval IDs stay put
        val nowSec = System.currentTimeMillis() / 1000
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = manager.queryEvents(fromSec * 1000, nowSec * 1000)
        val event = UsageEvents.Event()

        val spans = ArrayList<LongArray>()
        val perApp = HashMap<Pair<String, String>, Long>() // (day, package) -> seconds
        val open = HashMap<String, Long>() // package -> when it came to the foreground
        var screenOn = -1L

        fun addUsage(pkg: String, start: Long, end: Long) {
            var s = start
            while (s < end) {
                val day = Instant.ofEpochSecond(s).atZone(zone).toLocalDate()
                val e = minOf(end, startOfDay(day.plusDays(1)))
                perApp.merge(day.toString() to pkg, e - s) { a, b -> a + b }
                s = e
            }
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val t = event.timeStamp / 1000
            val pkg = event.packageName ?: ""
            when (event.eventType) {
                SCREEN_INTERACTIVE -> screenOn = t
                SCREEN_NON_INTERACTIVE -> {
                    if (screenOn >= 0) spans.add(longArrayOf(screenOn, t))
                    screenOn = -1
                    open.forEach { (p, start) -> addUsage(p, start, t) }
                    open.clear()
                }
                ACTIVITY_RESUMED -> if (pkg.isNotEmpty()) {
                    open[pkg]?.let { addUsage(pkg, it, t) }
                    open[pkg] = t
                }
                ACTIVITY_PAUSED -> if (pkg.isNotEmpty()) {
                    open.remove(pkg)?.let { addUsage(pkg, it, t) }
                }
            }
        }
        if (screenOn >= 0) { // the screen is on right now
            spans.add(longArrayOf(screenOn, nowSec))
            open.forEach { (p, start) -> addUsage(p, start, nowSec) }
        }

        val merged = ArrayList<LongArray>()
        for (s in spans.sortedBy { it[0] }) {
            val last = merged.lastOrNull()
            if (last != null && s[0] - last[1] <= JOIN_GAP) last[1] = maxOf(last[1], s[1]) else merged.add(longArrayOf(s[0], s[1]))
        }
        val db = Graph.db
        val install = Graph.prefs.installId
        val keep = HashSet<String>()
        for (s in merged) {
            if (s[1] - s[0] < MIN_SPAN) continue
            val id = stableId(install, "phone_active", s[0])
            keep.add(id)
            db.saveLocal(Interval(id, "phone_active", s[0], s[1], "tracker", 0, true, false, false))
        }
        // Periods that merged into a neighbour, or no longer exist, are removed (and the removal is sent).
        for (old in db.mineStarting("phone_active", fromSec, nowSec + 1)) {
            if (old.id !in keep) db.saveLocal(old.copy(deleted = true))
        }

        val labels = HashMap<String, String>()
        val perLabel = HashMap<Pair<String, String>, Long>()
        for ((key, seconds) in perApp) {
            val label = labels.getOrPut(key.second) { labelOf(context.packageManager, key.second) }
            perLabel.merge(key.first to label, seconds) { a, b -> a + b }
        }
        for ((key, seconds) in perLabel) db.saveUsage(key.first, key.second, seconds)
    }

    private fun labelOf(pm: PackageManager, pkg: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }
}

/** Sleep from Health Connect, which a watch or band app (e.g. Mi Fitness) can write into. */
object SleepCollector {
    val permissions: Set<String> = setOf(HealthPermission.getReadPermission(SleepSessionRecord::class))

    fun available(context: Context): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    suspend fun hasPermission(context: Context): Boolean =
        available(context) &&
            HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions().containsAll(permissions)

    /** Reads the last three days of sleep sessions and updates the phone's rows. Returns how many it saw. */
    suspend fun scan(context: Context): Int {
        if (!hasPermission(context)) return 0
        val client = HealthConnectClient.getOrCreate(context)
        val end = Instant.now()
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(end.minus(Duration.ofDays(3)), end),
            ),
        )
        val install = Graph.prefs.installId
        var seen = 0
        for (r in response.records) {
            val start = r.startTime.epochSecond
            val stop = r.endTime.epochSecond
            if (stop <= start) continue
            Graph.db.saveLocal(Interval(stableId(install, "sleep", start), "sleep", start, stop, "health_connect", 0, true, false, false))
            seen++
        }
        return seen
    }
}
