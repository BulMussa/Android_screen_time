package com.screentime.mobile

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URLEncoder
import java.time.DateTimeException
import java.time.Instant

/** Sends what changed on this phone and fetches what other devices sent. */
object SyncEngine {
    private const val BATCH = 1000
    private val lock = Any()

    /** Returns how many rows the server rejected; throws [SyncException]. */
    fun run(): Int = synchronized(lock) {
        val server = Graph.prefs.server
        val token = Graph.prefs.token
        if (server == null || token == null) throw SyncException("Not connected")
        try {
            val rejected = push(server, token)
            pull(server, token)
            rejected
        } catch (e: JSONException) {
            throw SyncException("Unexpected answer from the server")
        } catch (e: DateTimeException) {
            throw SyncException("Unexpected answer from the server")
        }
    }

    private fun intervalJson(i: Interval): JSONObject = JSONObject()
        .put("id", i.id)
        .put("kind", i.kind)
        .put("start_utc", isoUtc(i.start))
        .put("end_utc", isoUtc(i.end))
        .put("source", i.source)
        .put("updated_at", isoUtcMs(i.updatedMs))
        .put("deleted_at", if (i.deleted) isoUtcMs(i.updatedMs) else JSONObject.NULL)

    private fun usageJson(u: Usage): JSONObject {
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

    private fun rejectedIds(part: JSONObject): Set<String> {
        val list = part.getJSONArray("rejected")
        return (0 until list.length()).map { list.getJSONObject(it).getString("id") }.toSet()
    }

    private fun push(server: String, token: String): Int {
        val db = Graph.db
        var rejectedCount = 0
        while (true) {
            val intervals = db.dirtyIntervals(BATCH)
            val usage = db.dirtyUsage(BATCH)
            if (intervals.isEmpty() && usage.isEmpty()) return rejectedCount
            val body = JSONObject()
                .put("intervals", JSONArray().also { a -> intervals.forEach { a.put(intervalJson(it)) } })
                .put("app_usage_daily", JSONArray().also { a -> usage.forEach { a.put(usageJson(it)) } })
            val result = Api.call(server, "POST", "/v1/sync/push", body, token)
            val badIntervals = rejectedIds(result.getJSONObject("intervals"))
            // The server names an app usage row as "<date>|<app>|<monitor>"; phones have no monitor.
            val badUsage = rejectedIds(result.getJSONObject("app_usage_daily"))
            db.markClean(intervals.filter { it.id !in badIntervals })
            db.markUsageClean(usage.filter { "${it.day}|${it.app.take(255)}|" !in badUsage })
            rejectedCount += badIntervals.size + badUsage.size
            // Rejected rows stay unsent; stop here, or the next round would send them again forever.
            if (badIntervals.isNotEmpty() || badUsage.isNotEmpty()) return rejectedCount
        }
    }

    private fun pull(server: String, token: String) {
        val prefs = Graph.prefs
        var restarted = false
        while (true) {
            val query = "cursor=${URLEncoder.encode(prefs.cursor, "UTF-8")}&limit=$BATCH"
            val page = try {
                Api.call(server, "GET", "/v1/sync/pull?$query", null, token)
            } catch (e: SyncException) {
                if (e.status == 400 && prefs.cursor != "0" && !restarted) {
                    // The server doesn't know our cursor (e.g. it was restored from an older backup).
                    prefs.cursor = "0"
                    Graph.db.deleteRemote()
                    restarted = true
                    continue
                }
                throw e
            }
            val rows = page.getJSONArray("intervals")
            for (n in 0 until rows.length()) {
                val r = rows.getJSONObject(n)
                Graph.db.applyRemote(
                    Interval(
                        id = r.getString("id"),
                        kind = r.getString("kind"),
                        start = Instant.parse(r.getString("start_utc")).epochSecond,
                        end = Instant.parse(r.getString("end_utc")).epochSecond,
                        source = r.getString("source"),
                        updatedMs = Instant.parse(r.getString("updated_at")).toEpochMilli(),
                        mine = false,
                        deleted = !r.isNull("deleted_at"),
                        dirty = false,
                    ),
                )
            }
            prefs.cursor = page.getString("cursor") // moved only after the page is saved
            if (!page.getBoolean("has_more")) return
        }
    }
}
