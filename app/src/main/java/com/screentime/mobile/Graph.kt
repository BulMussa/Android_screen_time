package com.screentime.mobile

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** The app's shared storage, created once in [ScreenTimeApp]. */
object Graph {
    private lateinit var appContext: Context
    val prefs: Prefs by lazy { Prefs(appContext) }
    val db: Db by lazy { Db(appContext) }

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}

fun nowMs(): Long = System.currentTimeMillis()

/** The same input always gives the same ID, so a growing interval keeps its identity on the server. */
fun stableId(vararg parts: Any): String =
    UUID.nameUUIDFromBytes(parts.joinToString("|").toByteArray()).toString()

/** Seconds since the epoch of the phone's local midnight that starts [day]. */
fun startOfDay(day: LocalDate): Long = day.atStartOfDay(ZoneId.systemDefault()).toEpochSecond()

fun isoUtc(epochSeconds: Long): String = Instant.ofEpochSecond(epochSeconds).toString()

fun isoUtcMs(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()

/** 3h 05m style, like the Windows app. */
fun short(seconds: Long): String {
    val minutes = seconds / 60
    return "${minutes / 60}h ${(minutes % 60).toString().padStart(2, '0')}m"
}

/** 07:05 from seconds after midnight. */
fun hm(seconds: Long): String =
    "${(seconds / 3600).toString().padStart(2, '0')}:${(seconds % 3600 / 60).toString().padStart(2, '0')}"
