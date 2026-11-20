package com.screentime.mobile

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URLEncoder
import java.time.DateTimeException
import java.time.Instant

/** The rows the other side refused; they stay unsent. */
data class Rejected(val intervals: List<Interval>, val usage: List<Usage>) {
    val count: Int get() = intervals.size + usage.size
}

/** Intervals changed since a cursor, the cursor to ask with next time, and whether more are waiting. */
class Page(val intervals: List<Interval>, val cursor: String, val hasMore: Boolean)

/** A transport that isn't set up: no server connected, or no laptop paired. The message says which. */
class NotSetUpException(message: String) : SyncException(message)

/** The other side doesn't know the pull cursor (e.g. the server was restored from an older backup). */
class CursorUnknownException(message: String) : SyncException(message, 400)

/**
 * One way of swapping rows with other devices. What was sent to it and its pull cursor are kept per
 * transport (the "sent" table in [Db] and [Prefs.cursor]), so switching the sync mode never sends twice.
 */
interface Transport {
    /** "server" or "direct"; names its progress. */
    val name: String

    /** Sends rows changed on this phone. Returns the ones the other side rejected; throws [SyncException]. */
    fun send(intervals: List<Interval>, usage: List<Usage>): Rejected

    /** Intervals changed since [cursor] ("0" = from the start); throws [CursorUnknownException] or [SyncException]. */
    fun fetch(cursor: String, limit: Int): Page

    /** A word for the sync status after a sync, e.g. how the other side was found; null for none. */
    val note: String? get() = null
}

/** Your own Screen Time server: POST /v1/sync/push and GET /v1/sync/pull. */
class ServerTransport(private val server: String, private val token: String) : Transport {
    override val name = "server"

    override fun send(intervals: List<Interval>, usage: List<Usage>): Rejected {
        val result = Api.call(server, "POST", "/v1/sync/push", Rows.pushBody(intervals, usage), token)
        return parsed { Rows.rejected(result, intervals, usage) }
    }

    override fun fetch(cursor: String, limit: Int): Page {
        val query = "cursor=${URLEncoder.encode(cursor, "UTF-8")}&limit=$limit"
        val page = try {
            Api.call(server, "GET", "/v1/sync/pull?$query", null, token)
        } catch (e: SyncException) {
            if (e.status == 400) throw CursorUnknownException(e.message ?: "Unknown cursor")
            throw e
        }
        return parsed {
            val rows = page.getJSONArray("intervals")
            Page(
                (0 until rows.length()).map { Rows.parseInterval(rows.getJSONObject(it), mine = false) },
                page.getString("cursor"),
                page.getBoolean("has_more"),
            )
        }
    }

    private inline fun <T> parsed(block: () -> T): T = try {
        block()
    } catch (e: JSONException) {
        throw SyncException("Unexpected answer from the server")
    } catch (e: DateTimeException) {
        throw SyncException("Unexpected answer from the server")
    }
}

/** Rows as JSON, in the server's format; backups use the same format. */
object Rows {
    /** The body of a push, the same for the server and the laptop. */
    fun pushBody(intervals: List<Interval>, usage: List<Usage>): JSONObject = JSONObject()
        .put("intervals", JSONArray().also { a -> intervals.forEach { a.put(intervalJson(it)) } })
        .put("app_usage_daily", JSONArray().also { a -> usage.forEach { a.put(usageJson(it)) } })

    /** The rows a push answer names as rejected; throws JSONException. */
    fun rejected(result: JSONObject, intervals: List<Interval>, usage: List<Usage>): Rejected {
        fun ids(part: JSONObject): Set<String> {
            val list = part.getJSONArray("rejected")
            return (0 until list.length()).map { list.getJSONObject(it).getString("id") }.toSet()
        }
        val badIntervals = ids(result.getJSONObject("intervals"))
        // An app usage row is named "<date>|<app>|<monitor>"; phones have no monitor.
        val badUsage = ids(result.getJSONObject("app_usage_daily"))
        return Rejected(intervals.filter { it.id in badIntervals }, usage.filter { "${it.day}|${it.app.take(255)}|" in badUsage })
    }

    fun intervalJson(i: Interval): JSONObject = JSONObject()
        .put("id", i.id)
        .put("kind", i.kind)
        .put("start_utc", isoUtc(i.start))
        .put("end_utc", isoUtc(i.end))
        .put("source", i.source)
        .put("updated_at", isoUtcMs(i.updatedMs))
        .put("deleted_at", if (i.deleted) isoUtcMs(i.updatedMs) else JSONObject.NULL)

    fun usageJson(u: Usage): JSONObject {
        val seconds = minOf(u.seconds, 90_000L)
        return JSONObject()
            .put("local_date", u.day)
            .put("app", u.app.take(255))
            .put("monitor", JSONObject.NULL)
            .put("focus_s", seconds)
            .put("cursor_s", 0)
            .put("active_s", seconds)
            .put("updated_at", isoUtcMs(u.updatedMs))
    }

    /**
     * Throws JSONException or DateTimeException for a damaged row. A deleted row may come without times
     * (the Windows app's backups leave them out); a missing source is left empty for the caller to fill.
     */
    fun parseInterval(r: JSONObject, mine: Boolean): Interval {
        val deleted = !r.isNull("deleted_at")
        fun time(field: String): Long =
            if (deleted && r.isNull(field)) 0 else Instant.parse(r.getString(field)).epochSecond
        val id = r.getString("id")
        val kind = r.getString("kind")
        if (id.isBlank() || kind.isBlank()) throw JSONException("row without id or kind")
        return Interval(
            id = id,
            kind = kind,
            start = time("start_utc"),
            end = time("end_utc"),
            source = r.optString("source", ""),
            updatedMs = Instant.parse(r.getString("updated_at")).toEpochMilli(),
            mine = mine,
            deleted = deleted,
        )
    }

    fun parseUsage(r: JSONObject): Usage {
        val day = r.getString("local_date")
        java.time.LocalDate.parse(day) // checks the date
        return Usage(
            day = day,
            app = r.getString("app"),
            seconds = r.optLong("active_s", r.optLong("focus_s", 0)),
            updatedMs = Instant.parse(r.getString("updated_at")).toEpochMilli(),
        )
    }
}
