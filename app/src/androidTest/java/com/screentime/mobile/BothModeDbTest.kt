package com.screentime.mobile

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Both" against the real SQLite database: the same row by both routes, conflicts, forgetting one route, the
 * timelines and the upgrade to version 3. Uses its own throwaway database files, never the app's.
 */
@RunWith(AndroidJUnit4::class)
class BothModeDbTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: Db

    private class Cursors : CursorStore {
        val values = HashMap<String, String>()
        override fun cursor(transport: String) = values[transport] ?: "0"
        override fun setCursor(transport: String, value: String) { values[transport] = value }
    }

    private class Remote(override val name: String, vararg rows: Interval) : Transport {
        val feed = rows.toMutableList()
        var down = false
        override fun send(intervals: List<Interval>, usage: List<Usage>): Rejected {
            if (down) throw SyncException("Can't reach $name")
            return Rejected(emptyList(), emptyList())
        }
        override fun fetch(cursor: String, limit: Int): Page {
            if (down) throw SyncException("Can't reach $name")
            val from = cursor.toInt()
            val to = minOf(from + limit, feed.size)
            return Page(feed.subList(from, to).toList(), to.toString(), to < feed.size)
        }
    }

    private fun row(id: String, updated: Long, start: Long = 1_000, end: Long = 2_000, deleted: Boolean = false) =
        Interval(id, "pc_on", start, end, "tracker", updated, mine = false, deleted = deleted)

    private fun both(server: Remote, laptop: Remote, store: Db = db, cursors: Cursors = Cursors()) =
        SyncCore(store, cursors).syncAll(listOf("direct", "server")) { if (it == "server") server else laptop }

    @Before
    fun setUp() {
        context.deleteDatabase(NAME)
        db = Db(context, NAME)
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(NAME)
        context.deleteDatabase(OLD)
    }

    @Test
    fun theSameRowByBothRoutesIsStoredOnce() {
        val r = row("pc-1", 100)
        assertEquals(1, db.applyRemote(listOf(r), "server"))
        assertEquals("the second route's copy isn't written again", 0, db.applyRemote(listOf(r), "direct"))
        assertEquals(r, db.get("pc-1"))
        assertEquals(RowMerge.ROUTE_SERVER or RowMerge.ROUTE_DIRECT, db.routes("pc-1"))
    }

    @Test
    fun conflictsResolveTheSameInEitherOrder() {
        val serverRows = arrayOf(row("A", 100, end = 2_000), row("B", 300, deleted = true), row("C", 250, end = 2_500), row("D", 400))
        val laptopRows = arrayOf(row("A", 200, end = 2_200), row("B", 200, end = 2_300), row("C", 150, deleted = true), row("D", 400, deleted = true))
        both(Remote("server", *serverRows), Remote("direct", *laptopRows)) // the laptop first
        val second = Db(context, OLD) // the server first
        SyncCore(second, Cursors()).syncAll(listOf("server", "direct")) {
            if (it == "server") Remote("server", *serverRows) else Remote("direct", *laptopRows)
        }
        for (id in listOf("A", "B", "C", "D")) assertEquals(id, db.get(id), second.get(id))
        assertEquals(2_200L, db.get("A")!!.end)
        assertTrue(db.get("B")!!.deleted)
        assertFalse(db.get("C")!!.deleted)
        assertTrue(db.get("D")!!.deleted)
        second.close()
    }

    @Test
    fun forgettingTheServerKeepsWhatTheLaptopBrought() {
        both(Remote("server", row("onlyServer", 1), row("shared", 1)), Remote("direct", row("onlyLaptop", 1), row("shared", 1)))
        db.saveLocal(Interval("mine", "phone_active", 1_000, 2_000, "tracker", 0, true, false))
        db.mergeBackup(listOf(row("imported", 1)), emptyList())
        db.forgetRoute("server") // disconnecting the server
        assertNull(db.get("onlyServer"))
        assertEquals(RowMerge.ROUTE_DIRECT, db.routes("shared"))
        assertTrue(db.get("onlyLaptop") != null && db.get("mine") != null && db.get("imported") != null)
    }

    @Test
    fun serverDownLaptopStillSyncs() {
        val server = Remote("server", row("s1", 1)).apply { down = true }
        val outcomes = both(server, Remote("direct", row("l1", 1)))
        assertNull(outcomes.single { it.transport == "direct" }.error)
        assertTrue(outcomes.single { it.transport == "server" }.error != null)
        assertTrue(db.get("l1") != null && db.get("s1") == null)
    }

    @Test
    fun timelinesAreTheSameWhicheverRouteBroughtTheRows() {
        val rows = arrayOf(row("x", 1, start = 3_600, end = 7_200), row("y", 1, start = 7_000, end = 9_000))
        both(Remote("server", *rows), Remote("direct"))
        val viaServer = Timelines.cut(db.overlapping(listOf("pc_on"), 0, 86_400), 0, 86_400)
        val laptopDb = Db(context, OLD)
        both(Remote("server"), Remote("direct", *rows), store = laptopDb)
        val viaLaptop = Timelines.cut(laptopDb.overlapping(listOf("pc_on"), 0, 86_400), 0, 86_400)
        laptopDb.close()
        assertEquals(viaServer, viaLaptop)
        assertEquals(listOf(Span(3_600, 9_000)), viaServer)
    }

    @Test
    fun upgradeFromVersion2CountsExistingRowsAsBothRoutes() {
        db.close()
        context.deleteDatabase(OLD)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(OLD), null).use { old ->
            old.execSQL(
                "CREATE TABLE intervals (id TEXT PRIMARY KEY, kind TEXT NOT NULL, start_ts INTEGER NOT NULL, " +
                    "end_ts INTEGER NOT NULL, source TEXT NOT NULL, updated_ts INTEGER NOT NULL, " +
                    "mine INTEGER NOT NULL, deleted INTEGER NOT NULL)",
            )
            old.execSQL("CREATE INDEX intervals_start ON intervals (start_ts)")
            old.execSQL("CREATE TABLE app_usage (day TEXT NOT NULL, app TEXT NOT NULL, seconds INTEGER NOT NULL, updated_ts INTEGER NOT NULL, PRIMARY KEY (day, app))")
            old.execSQL("CREATE TABLE sent (transport TEXT NOT NULL, tbl TEXT NOT NULL, key TEXT NOT NULL, updated_ts INTEGER NOT NULL, PRIMARY KEY (transport, tbl, key))")
            old.execSQL("INSERT INTO intervals VALUES ('theirs', 'pc_on', 1, 2, 'tracker', 10, 0, 0)")
            old.execSQL("INSERT INTO intervals VALUES ('mine', 'phone_active', 1, 2, 'tracker', 10, 1, 0)")
            old.version = 2
        }
        val upgraded = Db(context, OLD)
        assertEquals(RowMerge.ROUTE_SERVER or RowMerge.ROUTE_DIRECT, upgraded.routes("theirs"))
        assertEquals(0, upgraded.routes("mine"))
        upgraded.forgetRoute("server")
        assertTrue("a row of unknown route survives forgetting one route", upgraded.get("theirs") != null)
        upgraded.close()
        db = Db(context, NAME)
    }

    private companion object {
        const val NAME = "both-mode-test.db"
        const val OLD = "both-mode-test-2.db"
    }
}
