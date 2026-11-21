package com.screentime.mobile

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/**
 * A period of one kind: "pc_on", "pc_active", "phone_active" or "sleep".
 * Times are seconds since the epoch; [mine] marks rows made on this phone (the ones it sends).
 */
data class Interval(
    val id: String,
    val kind: String,
    val start: Long,
    val end: Long,
    val source: String,
    val updatedMs: Long,
    val mine: Boolean,
    val deleted: Boolean,
)

/** Seconds an app was in the foreground on one local day. */
data class Usage(val day: String, val app: String, val seconds: Long, val updatedMs: Long) {
    /** Names the row in the sent table ([day] is always 10 characters, so this is unambiguous). */
    val key: String get() = "$day|$app"
}

/**
 * The local database. What was sent is kept per transport in the "sent" table: a row of this phone
 * is waiting for a transport until that transport has a record of it at its current updated_ts.
 * Each row from another device remembers which routes delivered it ("routes", [RowMerge] bits), so
 * forgetting one route (disconnecting the server) leaves what the other route brought. [name] is for tests.
 *
 * Upgrades run in one transaction with the version change (SQLiteOpenHelper), so an interrupted upgrade
 * leaves the old version as it was, and the routes step checks the schema, so repeating it changes nothing.
 * A damaged file is moved aside as "<name>.damaged-<time>" (never deleted, unlike Android's default) and
 * an empty database takes its place; [onFresh] runs whenever a database is created empty, so the caller
 * can pull everything again (its cursors would otherwise skip the rows the old file had).
 */
class Db(context: Context, name: String = "screentime.db", private val onFresh: () -> Unit = {}) :
    SQLiteOpenHelper(context, name, null, 3, KeepDamaged), SyncStore {
    override fun onCreate(db: SQLiteDatabase) {
        createIntervals(db, "intervals")
        db.execSQL("CREATE INDEX intervals_start ON intervals (start_ts)")
        createUsage(db, "app_usage")
        createSent(db)
        onFresh()
    }

    /** Moves a damaged database file aside instead of deleting it, so it can still be recovered by hand. */
    private object KeepDamaged : DatabaseErrorHandler {
        override fun onCorruption(db: SQLiteDatabase) {
            val path = db.path
            if (db.isOpen) runCatching { db.close() }
            moveAside(path)
        }
    }

    companion object {
        private const val COLS = "id, kind, start_ts, end_ts, source, updated_ts, mine, deleted"

        /** Renames [path] and its journal files to "<path>.damaged-<time>..."; returns the new main file, if any. */
        fun moveAside(path: String): File? {
            if (path == ":memory:" || path.isEmpty()) return null
            val stamp = System.currentTimeMillis()
            var moved: File? = null
            for (suffix in listOf("", "-journal", "-wal", "-shm")) {
                val file = File(path + suffix)
                val aside = File("$path.damaged-$stamp$suffix")
                if (file.exists() && file.renameTo(aside) && suffix.isEmpty()) moved = aside
            }
            return moved
        }

        private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
            db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                val name = c.getColumnIndexOrThrow("name")
                generateSequence { if (c.moveToNext()) c.getString(name) else null }.any { it == column }
            }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Version 1 had one "dirty" flag, which meant "not sent to the server yet".
            createSent(db)
            db.execSQL(
                "INSERT INTO sent SELECT 'server', 'intervals', id, updated_ts FROM intervals WHERE mine = 1 AND dirty = 0",
            )
            db.execSQL(
                "INSERT INTO sent SELECT 'server', 'app_usage', day || '|' || app, updated_ts FROM app_usage WHERE dirty = 0",
            )
            db.execSQL( // the version 2 table, without the routes of version 3
                "CREATE TABLE intervals_v2 (id TEXT PRIMARY KEY, kind TEXT NOT NULL, start_ts INTEGER NOT NULL, " +
                    "end_ts INTEGER NOT NULL, source TEXT NOT NULL, updated_ts INTEGER NOT NULL, " +
                    "mine INTEGER NOT NULL, deleted INTEGER NOT NULL)",
            )
            db.execSQL(
                "INSERT INTO intervals_v2 SELECT id, kind, start_ts, end_ts, source, updated_ts, mine, deleted FROM intervals",
            )
            db.execSQL("DROP TABLE intervals")
            db.execSQL("ALTER TABLE intervals_v2 RENAME TO intervals")
            db.execSQL("CREATE INDEX intervals_start ON intervals (start_ts)")
            createUsage(db, "app_usage_v2")
            db.execSQL("INSERT INTO app_usage_v2 SELECT day, app, seconds, updated_ts FROM app_usage")
            db.execSQL("DROP TABLE app_usage")
            db.execSQL("ALTER TABLE app_usage_v2 RENAME TO app_usage")
        }
        if (oldVersion < 3 && !hasColumn(db, "intervals", "routes")) {
            // Skipped when the column is already there (the step repeated), so routes recorded since stay as
            // they are. A row left with routes 0 is never forgotten (see forgetRoute), so that's safe too.
            db.execSQL("ALTER TABLE intervals ADD COLUMN routes INTEGER NOT NULL DEFAULT 0")
            // Which route brought the rows already here isn't known: count them as both, so that forgetting
            // one route never drops what the other may have delivered.
            db.execSQL("UPDATE intervals SET routes = ${RowMerge.ROUTE_SERVER or RowMerge.ROUTE_DIRECT} WHERE mine = 0")
        }
    }

    private fun createIntervals(db: SQLiteDatabase, name: String) = db.execSQL(
        "CREATE TABLE $name (id TEXT PRIMARY KEY, kind TEXT NOT NULL, start_ts INTEGER NOT NULL, " +
            "end_ts INTEGER NOT NULL, source TEXT NOT NULL, updated_ts INTEGER NOT NULL, " +
            "mine INTEGER NOT NULL, deleted INTEGER NOT NULL, routes INTEGER NOT NULL DEFAULT 0)",
    )

    private fun createUsage(db: SQLiteDatabase, name: String) = db.execSQL(
        "CREATE TABLE $name (day TEXT NOT NULL, app TEXT NOT NULL, seconds INTEGER NOT NULL, " +
            "updated_ts INTEGER NOT NULL, PRIMARY KEY (day, app))",
    )

    /** [tbl] is "intervals" or "app_usage"; [key] the interval ID or [Usage.key]. */
    private fun createSent(db: SQLiteDatabase) = db.execSQL(
        "CREATE TABLE sent (transport TEXT NOT NULL, tbl TEXT NOT NULL, key TEXT NOT NULL, " +
            "updated_ts INTEGER NOT NULL, PRIMARY KEY (transport, tbl, key))",
    )

    private fun read(c: Cursor) = Interval(
        id = c.getString(0), kind = c.getString(1), start = c.getLong(2), end = c.getLong(3),
        source = c.getString(4), updatedMs = c.getLong(5), mine = c.getInt(6) == 1, deleted = c.getInt(7) == 1,
    )

    private fun query(where: String, args: Array<String>, tail: String = ""): List<Interval> =
        readableDatabase.rawQuery("SELECT $COLS FROM intervals WHERE $where $tail", args).use { c ->
            val rows = ArrayList<Interval>()
            while (c.moveToNext()) rows.add(read(c))
            rows
        }

    private fun queryUsage(where: String, args: Array<String>, tail: String = ""): List<Usage> =
        readableDatabase.rawQuery("SELECT day, app, seconds, updated_ts FROM app_usage WHERE $where $tail", args).use { c ->
            val rows = ArrayList<Usage>()
            while (c.moveToNext()) rows.add(Usage(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3)))
            rows
        }

    /** [routes]: the routes that delivered a row from another device (0 for this phone's own and imported rows). */
    private fun put(i: Interval, routes: Int = 0) {
        val values = ContentValues().apply {
            put("id", i.id)
            put("kind", i.kind)
            put("start_ts", i.start)
            put("end_ts", i.end)
            put("source", i.source)
            put("updated_ts", i.updatedMs)
            put("mine", if (i.mine) 1 else 0)
            put("deleted", if (i.deleted) 1 else 0)
            put("routes", routes)
        }
        writableDatabase.insertWithOnConflict("intervals", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun putUsage(u: Usage) {
        val values = ContentValues().apply {
            put("day", u.day)
            put("app", u.app)
            put("seconds", u.seconds)
            put("updated_ts", u.updatedMs)
        }
        writableDatabase.insertWithOnConflict("app_usage", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun recordSent(transport: String, tbl: String, key: String, updatedMs: Long) {
        val values = ContentValues().apply {
            put("transport", transport)
            put("tbl", tbl)
            put("key", key)
            put("updated_ts", updatedMs)
        }
        writableDatabase.insertWithOnConflict("sent", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private inline fun <T> transaction(block: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            return block().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun get(id: String): Interval? = query("id = ?", arrayOf(id)).firstOrNull()

    @Synchronized
    fun getUsage(day: String, app: String): Usage? = queryUsage("day = ? AND app = ?", arrayOf(day, app)).firstOrNull()

    /** A change made on this phone: saved and marked to be sent, unless nothing changed. */
    @Synchronized
    fun saveLocal(i: Interval) {
        val old = get(id = i.id)
        if (old != null && old.kind == i.kind && old.start == i.start && old.end == i.end && old.deleted == i.deleted) {
            return
        }
        // Strictly newer than before, so the other side accepts the change.
        put(i.copy(updatedMs = maxOf(nowMs(), (old?.updatedMs ?: 0L) + 1), mine = true))
    }

    /** The routes recorded for a row (0 if it's unknown or this phone's own). */
    private fun routesOf(id: String): Int =
        readableDatabase.rawQuery("SELECT routes FROM intervals WHERE id = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    /**
     * Rows fetched from [transport], merged with [RowMerge]: the newer copy wins, a deletion wins a tie, and
     * the same row arriving by both routes is stored once. Either way the route is remembered. Returns how
     * many were stored.
     */
    @Synchronized
    override fun applyRemote(rows: List<Interval>, transport: String): Int = transaction {
        val route = RowMerge.route(transport)
        var stored = 0
        for (i in rows) {
            val old = get(i.id)
            val routes = (if (old != null) routesOf(i.id) else 0) or route
            if (!RowMerge.replaces(old, i)) {
                if (old != null && !old.mine) {
                    writableDatabase.execSQL("UPDATE intervals SET routes = ? WHERE id = ?", arrayOf<Any>(routes, i.id))
                }
                continue
            }
            put(i.copy(mine = old?.mine ?: false), if (old?.mine == true) 0 else routes)
            // One of our own rows, changed elsewhere: that transport has it already, so don't send it back.
            if (old?.mine == true) recordSent(transport, "intervals", i.id, i.updatedMs)
            stored++
        }
        stored
    }

    /** This phone's rows that [transport] doesn't have in their current version. */
    @Synchronized
    override fun pendingIntervals(transport: String, limit: Int): List<Interval> = query(
        "mine = 1 AND NOT EXISTS (SELECT 1 FROM sent s WHERE s.transport = ? AND s.tbl = 'intervals' " +
            "AND s.key = intervals.id AND s.updated_ts = intervals.updated_ts)",
        arrayOf(transport), "ORDER BY start_ts LIMIT $limit",
    )

    @Synchronized
    override fun pendingUsage(transport: String, limit: Int): List<Usage> = queryUsage(
        "NOT EXISTS (SELECT 1 FROM sent s WHERE s.transport = ? AND s.tbl = 'app_usage' " +
            "AND s.key = app_usage.day || '|' || app_usage.app AND s.updated_ts = app_usage.updated_ts)",
        arrayOf(transport), "ORDER BY day LIMIT $limit",
    )

    /**
     * Records that [transport] accepted these rows in the version that was sent. A row changed again
     * while being sent has a newer updated_ts, so it stays waiting.
     */
    @Synchronized
    override fun markSent(transport: String, intervals: List<Interval>, usage: List<Usage>) = transaction {
        for (r in intervals) recordSent(transport, "intervals", r.id, r.updatedMs)
        for (u in usage) recordSent(transport, "app_usage", u.key, u.updatedMs)
    }

    /** Sends everything made here to [transport] again, e.g. after connecting to a server. */
    @Synchronized
    fun forgetSent(transport: String) {
        writableDatabase.execSQL("DELETE FROM sent WHERE transport = ?", arrayOf(transport))
    }

    /** Rows of these kinds that overlap [from, to) (seconds), without deleted ones. */
    @Synchronized
    fun overlapping(kinds: List<String>, from: Long, to: Long): List<Interval> {
        val marks = kinds.joinToString(",") { "?" }
        return query(
            "deleted = 0 AND end_ts > ? AND start_ts < ? AND kind IN ($marks)",
            arrayOf(from.toString(), to.toString()) + kinds,
            "ORDER BY start_ts",
        )
    }

    /** Rows made on this phone that start in [from, to), without deleted ones. */
    @Synchronized
    fun mineStarting(kind: String, from: Long, to: Long): List<Interval> =
        query("deleted = 0 AND mine = 1 AND kind = ? AND start_ts >= ? AND start_ts < ?", arrayOf(kind, from.toString(), to.toString()))

    /** Every interval, deleted ones included (for a backup). */
    @Synchronized
    fun allIntervals(): List<Interval> = query("1", emptyArray(), "ORDER BY start_ts")

    /** This phone's app times of one local day (YYYY-MM-DD). */
    @Synchronized
    fun usageOn(day: String): List<Usage> = queryUsage("day = ?", arrayOf(day))

    @Synchronized
    fun allUsage(): List<Usage> = queryUsage("1", emptyArray(), "ORDER BY day, app")

    /**
     * Forgets what only [transport] delivered (e.g. after disconnecting the server); rows the other route also
     * delivered stay, and this phone's own and imported rows are never touched.
     */
    @Synchronized
    fun forgetRoute(transport: String) = transaction {
        val route = RowMerge.route(transport)
        writableDatabase.execSQL("DELETE FROM intervals WHERE mine = 0 AND routes = ?", arrayOf<Any>(route))
        writableDatabase.execSQL("UPDATE intervals SET routes = routes & ~? WHERE mine = 0", arrayOf<Any>(route))
    }

    /** The routes that delivered a row, for tests. */
    @Synchronized
    fun routes(id: String): Int = routesOf(id)

    @Synchronized
    fun saveUsage(day: String, app: String, seconds: Long) {
        val old = getUsage(day, app)
        if (old != null && old.seconds == seconds) return
        putUsage(Usage(day, app, seconds, maxOf(nowMs(), (old?.updatedMs ?: 0L) + 1)))
    }

    /**
     * Merges a backup in one transaction, so a failure changes nothing: each row with [RowMerge] (the newer
     * copy wins, a deletion wins a tie). A row that was ours stays ours; a row from another device keeps its routes. Returns (intervals stored, usage rows stored).
     */
    @Synchronized
    fun mergeBackup(intervals: List<Interval>, usage: List<Usage>): Pair<Int, Int> = transaction {
        var stored = 0
        for (i in intervals) {
            val old = get(i.id)
            if (!RowMerge.replaces(old, i)) continue
            val mine = i.mine || old?.mine == true
            put(i.copy(mine = mine, source = i.source.ifEmpty { old?.source ?: "imported" }), if (mine || old == null) 0 else routesOf(i.id))
            stored++
        }
        var usageStored = 0
        for (u in usage) {
            val old = getUsage(u.day, u.app)
            if (old != null && old.updatedMs >= u.updatedMs) continue
            putUsage(u)
            usageStored++
        }
        stored to usageStored
    }
}
