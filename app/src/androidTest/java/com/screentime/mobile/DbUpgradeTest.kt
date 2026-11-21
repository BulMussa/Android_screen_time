package com.screentime.mobile

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Upgrading to version 3 on copies of a version 2 database: nothing lost or changed, repeating it changes
 * nothing, an interrupted upgrade leaves version 2 as it was, and a damaged file is kept aside.
 *
 * The source is a copy of a real phone's database when one was copied into this app's files folder
 * as "upgrade-source.db" (see docs/testing-direct-sync.md in the Windows repo); otherwise a made-up
 * version 2 database. Only copies named "upgrade-*.db" in the databases folder are opened or written.
 */
@RunWith(AndroidJUnit4::class)
class DbUpgradeTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val work = "upgrade-work.db"
    private val workPath: File get() = context.getDatabasePath(work)

    /** All three tables, every column, as plain values (so a copy compares equal, row by row). */
    private data class Snapshot(
        val intervals: Map<String, List<Any?>>, // id -> the version 2 columns, then routes if there
        val usage: List<List<Any?>>,
        val sent: List<List<Any?>>,
        val version: Int,
    ) {
        fun v2Columns() = intervals.mapValues { it.value.take(8) }
        fun countsByKindAndOwner() = intervals.values.groupingBy { "${it[1]}/mine=${it[6]}" }.eachCount().toSortedMap()
    }

    private fun rows(db: SQLiteDatabase, sql: String): List<List<Any?>> = db.rawQuery(sql, null).use { c ->
        val out = ArrayList<List<Any?>>()
        while (c.moveToNext()) out.add((0 until c.columnCount).map { i ->
            when (c.getType(i)) {
                android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                android.database.Cursor.FIELD_TYPE_NULL -> null
                else -> c.getString(i)
            }
        })
        out
    }

    private fun hasRoutes(db: SQLiteDatabase) = rows(db, "PRAGMA table_info(intervals)").any { it[1] == "routes" }

    private fun snapshot(file: File): Snapshot =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val cols = "id, kind, start_ts, end_ts, source, updated_ts, mine, deleted" + if (hasRoutes(db)) ", routes" else ""
            Snapshot(
                rows(db, "SELECT $cols FROM intervals ORDER BY id").associateBy { it[0] as String },
                rows(db, "SELECT day, app, seconds, updated_ts FROM app_usage ORDER BY day, app"),
                rows(db, "SELECT transport, tbl, key, updated_ts FROM sent ORDER BY transport, tbl, key"),
                db.version,
            )
        }

    /** A fresh copy of the version 2 source at [work]. */
    private fun copySource(): File {
        val real = File(context.filesDir, "upgrade-source.db")
        deleteWork()
        workPath.parentFile!!.mkdirs()
        if (real.exists()) {
            real.copyTo(workPath)
            Log.i(TAG, "source: a copy of a real database, ${real.length()} bytes")
        } else {
            makeVersion2(workPath)
            Log.i(TAG, "source: a made-up version 2 database")
        }
        return workPath
    }

    private fun makeVersion2(file: File) = SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
        db.execSQL(
            "CREATE TABLE intervals (id TEXT PRIMARY KEY, kind TEXT NOT NULL, start_ts INTEGER NOT NULL, " +
                "end_ts INTEGER NOT NULL, source TEXT NOT NULL, updated_ts INTEGER NOT NULL, mine INTEGER NOT NULL, " +
                "deleted INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX intervals_start ON intervals (start_ts)")
        db.execSQL("CREATE TABLE app_usage (day TEXT NOT NULL, app TEXT NOT NULL, seconds INTEGER NOT NULL, updated_ts INTEGER NOT NULL, PRIMARY KEY (day, app))")
        db.execSQL("CREATE TABLE sent (transport TEXT NOT NULL, tbl TEXT NOT NULL, key TEXT NOT NULL, updated_ts INTEGER NOT NULL, PRIMARY KEY (transport, tbl, key))")
        for (n in 0 until 50) {
            val kind = listOf("pc_on", "pc_active", "phone_active", "sleep")[n % 4]
            val mine = if (kind.startsWith("pc")) 0 else 1
            db.execSQL("INSERT INTO intervals VALUES (?, ?, ?, ?, 'tracker', ?, ?, ?)",
                arrayOf<Any>("id-$n", kind, 1_700_000_000L + n * 60, 1_700_000_030L + n * 60, 1_700_000_000_000L + n, mine, n % 7 / 6))
        }
        db.execSQL("INSERT INTO app_usage VALUES ('2026-11-20', 'com.example', 120, 5)")
        db.execSQL("INSERT INTO sent VALUES ('server', 'intervals', 'id-2', 1700000000002)")
        db.version = 2
    }

    private fun openWithDb(onFresh: () -> Unit = {}): Db = Db(context, work, onFresh).also { it.readableDatabase }

    private fun deleteWork() {
        context.deleteDatabase(work)
        workPath.parentFile?.listFiles { f -> f.name.startsWith("$work.damaged-") }?.forEach { it.delete() }
    }

    @After
    fun tearDown() = deleteWork()

    @Test
    fun upgradeKeepsEveryRowAndMarksRemoteRowsAsBothRoutes() {
        val before = snapshot(copySource())
        assertEquals("the source is version 2", 2, before.version)
        openWithDb().close()
        val after = snapshot(workPath)
        Log.i(TAG, "rows by kind and owner: ${before.countsByKindAndOwner()}")
        assertEquals(3, after.version)
        assertEquals("row counts per kind and owner", before.countsByKindAndOwner(), after.countsByKindAndOwner())
        assertEquals("every row keeps its ID, times, updated_at, owner and deletion", before.v2Columns(), after.v2Columns())
        assertEquals("app times", before.usage, after.usage)
        assertEquals("what was sent", before.sent, after.sent)
        for ((id, row) in after.intervals) {
            val expected = if (row[6] == 0L) RowMerge.ROUTE_SERVER or RowMerge.ROUTE_DIRECT else 0
            assertEquals("routes of $id", expected.toLong(), row[8])
        }
        SQLiteDatabase.openDatabase(workPath.path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals("ok", rows(it, "PRAGMA integrity_check").single()[0])
        }
    }

    @Test
    fun upgradingTwiceChangesNothing() {
        copySource()
        openWithDb().close()
        // A route recorded after the upgrade, which a repeated upgrade mustn't overwrite
        val remoteId = snapshot(workPath).intervals.values.firstOrNull { it[6] == 0L }?.get(0) as String?
        SQLiteDatabase.openDatabase(workPath.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            if (remoteId != null) db.execSQL("UPDATE intervals SET routes = 1 WHERE id = ?", arrayOf(remoteId))
        }
        val once = snapshot(workPath)

        openWithDb().close() // opening a version 3 database again
        assertEquals(once, snapshot(workPath))

        // The upgrade itself again: the version mark set back to 2 on an upgraded file
        SQLiteDatabase.openDatabase(workPath.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 2 }
        openWithDb().close()
        assertEquals("repeating the upgrade changes no row", once.intervals, snapshot(workPath).intervals)
        assertEquals(3, snapshot(workPath).version)
    }

    @Test
    fun anInterruptedUpgradeLeavesVersion2AsItWas() {
        val before = snapshot(copySource())
        val helper = Db(context, "upgrade-unused.db")
        SQLiteDatabase.openDatabase(workPath.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            // SQLiteOpenHelper runs onUpgrade and the version change in one transaction; end it without
            // committing, as when the app is killed in the middle of the upgrade
            db.beginTransaction()
            try {
                helper.onUpgrade(db, 2, 3)
                db.version = 3
                assertTrue(hasRoutes(db))
            } finally {
                db.endTransaction()
            }
        }
        helper.close()
        context.deleteDatabase("upgrade-unused.db")
        assertEquals("nothing of the upgrade was kept", before, snapshot(workPath))
        SQLiteDatabase.openDatabase(workPath.path, null, SQLiteDatabase.OPEN_READONLY).use { assertFalse(hasRoutes(it)) }

        openWithDb().close() // the next start upgrades normally
        assertEquals(before.v2Columns(), snapshot(workPath).v2Columns())
    }

    @Test
    fun aPartlyUpgradedFileOpensWithoutLosingRows() {
        // The routes column there but the version still 2 (e.g. changed by another tool): no crash, no loss
        val before = snapshot(copySource())
        SQLiteDatabase.openDatabase(workPath.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("ALTER TABLE intervals ADD COLUMN routes INTEGER NOT NULL DEFAULT 0")
        }
        openWithDb().close()
        val after = snapshot(workPath)
        assertEquals(3, after.version)
        assertEquals(before.v2Columns(), after.v2Columns())
        // routes stay 0 (unknown), and forgetting a route never drops a row with routes 0
        Db(context, work).use { db ->
            db.forgetRoute("server")
            db.forgetRoute("direct")
        }
        assertEquals(before.v2Columns(), snapshot(workPath).v2Columns())
    }

    @Test
    fun aDamagedFileIsKeptAsideAndAnEmptyOneTakesItsPlace() {
        copySource()
        val damaged = ByteArray(8192) { (it * 31 % 251).toByte() } // not a database at all
        workPath.writeBytes(damaged)
        var fresh = 0
        val db = openWithDb { fresh++ }
        assertEquals("an empty database to start again", 0, db.allIntervals().size)
        assertEquals("the caller is told, to pull everything again", 1, fresh)
        db.close()
        val aside = workPath.parentFile!!.listFiles { f -> f.name.startsWith("$work.damaged-") && !f.name.endsWith("-journal") }!!
        assertEquals(1, aside.size)
        assertArrayEquals("the damaged file is kept as it was", damaged, aside.single().readBytes())
    }

    @Test
    fun aTruncatedFileIsKeptAside() {
        copySource()
        val bytes = workPath.readBytes()
        val cut = bytes.copyOf(maxOf(4096, bytes.size / 2)) // the end of the file lost
        workPath.writeBytes(cut)
        val outcome = runCatching {
            Db(context, work).use { db -> db.allIntervals().size }
        }
        Log.i(TAG, "a truncated file: $outcome")
        // Either way the bytes are still somewhere: in place, or moved aside
        val aside = workPath.parentFile!!.listFiles { f -> f.name.startsWith("$work.damaged-") && !f.name.contains("-journal") }!!
        val kept = if (aside.isNotEmpty()) aside.single().readBytes() else workPath.readBytes()
        assertArrayEquals(cut, kept)
        // And the app can open its database afterwards
        Db(context, work).use { db -> db.allIntervals() }
    }

    private companion object {
        const val TAG = "DbUpgradeTest"
    }
}
