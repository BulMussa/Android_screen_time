package com.screentime.mobile

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * A period of one kind: "pc_on", "pc_active", "phone_active" or "sleep".
 * Times are seconds since the epoch; [mine] marks rows made on this phone, [dirty] rows not sent yet.
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
    val dirty: Boolean,
)

/** Seconds an app was in the foreground on one local day. */
data class Usage(val day: String, val app: String, val seconds: Long, val updatedMs: Long)

class Db(context: Context) : SQLiteOpenHelper(context, "screentime.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE intervals (id TEXT PRIMARY KEY, kind TEXT NOT NULL, start_ts INTEGER NOT NULL, " +
                "end_ts INTEGER NOT NULL, source TEXT NOT NULL, updated_ts INTEGER NOT NULL, " +
                "mine INTEGER NOT NULL, deleted INTEGER NOT NULL, dirty INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX intervals_start ON intervals (start_ts)")
        db.execSQL(
            "CREATE TABLE app_usage (day TEXT NOT NULL, app TEXT NOT NULL, seconds INTEGER NOT NULL, " +
                "updated_ts INTEGER NOT NULL, dirty INTEGER NOT NULL, PRIMARY KEY (day, app))",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private fun read(c: Cursor) = Interval(
        id = c.getString(0), kind = c.getString(1), start = c.getLong(2), end = c.getLong(3),
        source = c.getString(4), updatedMs = c.getLong(5), mine = c.getInt(6) == 1,
        deleted = c.getInt(7) == 1, dirty = c.getInt(8) == 1,
    )

    private fun query(where: String, args: Array<String>, tail: String = ""): List<Interval> =
        readableDatabase.rawQuery("SELECT $COLS FROM intervals WHERE $where $tail", args).use { c ->
            val rows = ArrayList<Interval>()
            while (c.moveToNext()) rows.add(read(c))
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
            put("dirty", if (i.dirty) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("intervals", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun get(id: String): Interval? = query("id = ?", arrayOf(id)).firstOrNull()

    /** A change made on this phone: saved and marked to be sent, unless nothing changed. */
    @Synchronized
    fun saveLocal(i: Interval) {
        val old = get(id = i.id)
        if (old != null && old.kind == i.kind && old.start == i.start && old.end == i.end && old.deleted == i.deleted) {
            return
        }
        // Strictly newer than before, so the server accepts the change.
        put(i.copy(updatedMs = maxOf(nowMs(), (old?.updatedMs ?: 0L) + 1), mine = true, dirty = true))
    }

    /** A row from the server: kept only if it is newer than ours. Returns whether it was stored. */
    @Synchronized
    fun applyRemote(i: Interval): Boolean {
        val old = get(i.id)
        if (old != null && old.updatedMs >= i.updatedMs) return false
        put(i.copy(mine = old?.mine ?: false, dirty = false))
        return true
    }

    @Synchronized
    fun dirtyIntervals(limit: Int): List<Interval> = query("dirty = 1", emptyArray(), "ORDER BY start_ts LIMIT $limit")

    /** Marks sent rows as clean, unless they were changed again while being sent. */
    @Synchronized
    fun markClean(rows: List<Interval>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in rows) {
                db.execSQL("UPDATE intervals SET dirty = 0 WHERE id = ? AND updated_ts = ?", arrayOf<Any>(r.id, r.updatedMs))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
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

    /** Sends everything made here again, e.g. after connecting to a server. */
    @Synchronized
    fun markAllDirty() {
        writableDatabase.execSQL("UPDATE intervals SET dirty = 1 WHERE mine = 1")
        writableDatabase.execSQL("UPDATE app_usage SET dirty = 1")
    }

    /** Forgets what came from the server (the phone's own rows stay). */
    @Synchronized
    fun deleteRemote() {
        writableDatabase.execSQL("DELETE FROM intervals WHERE mine = 0")
    }

    @Synchronized
    fun saveUsage(day: String, app: String, seconds: Long) {
        val old = readableDatabase.rawQuery(
            "SELECT seconds, updated_ts FROM app_usage WHERE day = ? AND app = ?", arrayOf(day, app),
        ).use { c -> if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else null }
        if (old != null && old.first == seconds) return
        val values = ContentValues().apply {
            put("day", day)
            put("app", app)
            put("seconds", seconds)
            put("updated_ts", maxOf(nowMs(), (old?.second ?: 0L) + 1))
            put("dirty", 1)
        }
        writableDatabase.insertWithOnConflict("app_usage", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun dirtyUsage(limit: Int): List<Usage> =
        readableDatabase.rawQuery(
            "SELECT day, app, seconds, updated_ts FROM app_usage WHERE dirty = 1 ORDER BY day LIMIT $limit", null,
        ).use { c ->
            val rows = ArrayList<Usage>()
            while (c.moveToNext()) rows.add(Usage(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3)))
            rows
        }

    @Synchronized
    fun markUsageClean(rows: List<Usage>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in rows) {
                db.execSQL(
                    "UPDATE app_usage SET dirty = 0 WHERE day = ? AND app = ? AND updated_ts = ?",
                    arrayOf<Any>(r.day, r.app, r.updatedMs),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private companion object {
        const val COLS = "id, kind, start_ts, end_ts, source, updated_ts, mine, deleted, dirty"
    }
}
