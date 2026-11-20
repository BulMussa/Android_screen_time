package com.screentime.mobile

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

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
 */
class Db(context: Context) : SQLiteOpenHelper(context, "screentime.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        createIntervals(db, "intervals")
        db.execSQL("CREATE INDEX intervals_start ON intervals (start_ts)")
        createUsage(db, "app_usage")
        createSent(db)
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
            createIntervals(db, "intervals_v2")
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
    }

    private fun createIntervals(db: SQLiteDatabase, name: String) = db.execSQL(
        "CREATE TABLE $name (id TEXT PRIMARY KEY, kind TEXT NOT NULL, start_ts INTEGER NOT NULL, " +
            "end_ts INTEGER NOT NULL, source TEXT NOT NULL, updated_ts INTEGER NOT NULL, " +
            "mine INTEGER NOT NULL, deleted INTEGER NOT NULL)",
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

    private fun put(i: Interval) {
        val values = ContentValues().apply {
            put("id", i.id)
            put("kind", i.kind)
            put("start_ts", i.start)
            put("end_ts", i.end)
            put("source", i.source)
            put("updated_ts", i.updatedMs)
            put("mine", if (i.mine) 1 else 0)
            put("deleted", if (i.deleted) 1 else 0)
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

    /** Rows fetched from [transport]: each is kept only if it is newer than ours. Returns how many were stored. */
    @Synchronized
    fun applyRemote(rows: List<Interval>, transport: String): Int = transaction {
        var stored = 0
        for (i in rows) {
            val old = get(i.id)
            if (old != null && old.updatedMs >= i.updatedMs) continue
            put(i.copy(mine = old?.mine ?: false))
            // One of our own rows, changed elsewhere: that transport has it already, so don't send it back.
            if (old?.mine == true) recordSent(transport, "intervals", i.id, i.updatedMs)
            stored++
        }
        stored
    }

    /** This phone's rows that [transport] doesn't have in their current version. */
    @Synchronized
    fun pendingIntervals(transport: String, limit: Int): List<Interval> = query(
        "mine = 1 AND NOT EXISTS (SELECT 1 FROM sent s WHERE s.transport = ? AND s.tbl = 'intervals' " +
            "AND s.key = intervals.id AND s.updated_ts = intervals.updated_ts)",
        arrayOf(transport), "ORDER BY start_ts LIMIT $limit",
    )

    @Synchronized
    fun pendingUsage(transport: String, limit: Int): List<Usage> = queryUsage(
        "NOT EXISTS (SELECT 1 FROM sent s WHERE s.transport = ? AND s.tbl = 'app_usage' " +
            "AND s.key = app_usage.day || '|' || app_usage.app AND s.updated_ts = app_usage.updated_ts)",
        arrayOf(transport), "ORDER BY day LIMIT $limit",
    )

    /**
     * Records that [transport] accepted these rows in the version that was sent. A row changed again
     * while being sent has a newer updated_ts, so it stays waiting.
     */
    @Synchronized
    fun markSent(transport: String, intervals: List<Interval>, usage: List<Usage>) = transaction {
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

    @Synchronized
    fun allUsage(): List<Usage> = queryUsage("1", emptyArray(), "ORDER BY day, app")

    /** Forgets what came from other devices (the phone's own rows stay). */
    @Synchronized
    fun deleteRemote() {
        writableDatabase.execSQL("DELETE FROM intervals WHERE mine = 0")
    }

    @Synchronized
    fun saveUsage(day: String, app: String, seconds: Long) {
        val old = getUsage(day, app)
        if (old != null && old.seconds == seconds) return
        putUsage(Usage(day, app, seconds, maxOf(nowMs(), (old?.updatedMs ?: 0L) + 1)))
    }

    /**
     * Merges a backup in one transaction, so a failure changes nothing: for each row, the copy with the
     * newer updated_ts wins. A row that was ours stays ours. Returns (intervals stored, usage rows stored).
     */
    @Synchronized
    fun mergeBackup(intervals: List<Interval>, usage: List<Usage>): Pair<Int, Int> = transaction {
        var stored = 0
        for (i in intervals) {
            val old = get(i.id)
            if (old != null && old.updatedMs >= i.updatedMs) continue
            put(i.copy(mine = i.mine || old?.mine == true, source = i.source.ifEmpty { old?.source ?: "imported" }))
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

    private companion object {
        const val COLS = "id, kind, start_ts, end_ts, source, updated_ts, mine, deleted"
    }
}
