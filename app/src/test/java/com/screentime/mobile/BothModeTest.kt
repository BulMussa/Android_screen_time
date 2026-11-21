package com.screentime.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Both": the real SyncCore with a fake server and a fake laptop that feed overlapping and conflicting rows.
 * The store is in memory and merges with the same RowMerge rule as the database (BothModeDbTest on a phone
 * runs the same cases against SQLite).
 */
class BothModeTest {
    /** In-memory SyncStore: rows from other devices with their routes, this phone's rows and what each transport has. */
    class FakeStore : SyncStore {
        val remote = LinkedHashMap<String, Pair<Interval, Int>>()
        val own = ArrayList<Interval>()
        val sent = HashMap<String, MutableMap<String, Long>>()
        var stores = 0 // how many times a row was written

        override fun pendingIntervals(transport: String, limit: Int): List<Interval> =
            own.filter { sent[transport]?.get(it.id) != it.updatedMs }.take(limit)

        override fun pendingUsage(transport: String, limit: Int): List<Usage> = emptyList()

        override fun markSent(transport: String, intervals: List<Interval>, usage: List<Usage>) {
            for (i in intervals) sent.getOrPut(transport) { HashMap() }[i.id] = i.updatedMs
        }

        override fun applyRemote(rows: List<Interval>, transport: String): Int {
            var stored = 0
            for (i in rows) {
                val old = remote[i.id]
                val routes = (old?.second ?: 0) or RowMerge.route(transport)
                if (RowMerge.replaces(old?.first, i)) {
                    remote[i.id] = i to routes
                    stored++
                    stores++
                } else if (old != null) {
                    remote[i.id] = old.first to routes
                }
            }
            return stored
        }

        fun row(id: String) = remote[id]?.first
        fun live() = remote.values.map { it.first }.filter { !it.deleted }
    }

    class FakeCursors : CursorStore {
        val values = HashMap<String, String>()
        val moves = ArrayList<String>() // "<transport>=<cursor>", in order
        override fun cursor(transport: String) = values[transport] ?: "0"
        override fun setCursor(transport: String, value: String) {
            values[transport] = value
            moves.add("$transport=$value")
        }
    }

    /**
     * A server or laptop: a change feed (the cursor is the index into it) and what it was sent. [down] makes
     * every call fail as if it can't be reached; [forgetCursors] makes it refuse any cursor but "0" once.
     */
    class FakeRemote(override val name: String, vararg rows: Interval) : Transport {
        val feed = rows.toMutableList()
        val received = ArrayList<Interval>()
        var down = false
        var forgetCursors = false
        var fetches = 0

        override fun send(intervals: List<Interval>, usage: List<Usage>): Rejected {
            if (down) throw SyncException("Can't reach $name")
            received.addAll(intervals)
            return Rejected(emptyList(), emptyList())
        }

        override fun fetch(cursor: String, limit: Int): Page {
            if (down) throw SyncException("Can't reach $name")
            fetches++
            if (forgetCursors && cursor != "0") {
                forgetCursors = false
                throw CursorUnknownException("unknown cursor $cursor")
            }
            val from = cursor.toInt()
            val to = minOf(from + limit, feed.size)
            return Page(feed.subList(from, to).toList(), to.toString(), to < feed.size)
        }
    }

    private fun row(id: String, updated: Long, start: Long = 1_000, end: Long = 2_000, deleted: Boolean = false, kind: String = "pc_on") =
        Interval(id, kind, start, end, "tracker", updated, mine = false, deleted = deleted)

    /** Syncs "both" the way the app does: the laptop first, then the server. */
    private fun both(store: FakeStore, cursors: FakeCursors, server: FakeRemote, laptop: FakeRemote, batch: Int = 1000) =
        SyncCore(store, cursors, batch).syncAll(listOf("direct", "server")) { if (it == "server") server else laptop }

    // --- The merge rule ---

    @Test
    fun mergeRule() {
        val a = row("a", 100)
        assertTrue(RowMerge.replaces(null, a))
        assertTrue("newer wins", RowMerge.replaces(a, row("a", 101)))
        assertFalse("older loses", RowMerge.replaces(a, row("a", 99)))
        assertFalse("the same copy again is applied once", RowMerge.replaces(a, a))
        assertTrue("a newer deletion wins", RowMerge.replaces(a, row("a", 101, deleted = true)))
        assertFalse("an older deletion loses", RowMerge.replaces(a, row("a", 99, deleted = true)))
        assertTrue("on a tie the deletion wins", RowMerge.replaces(a, row("a", 100, deleted = true)))
        assertFalse("...whichever came first", RowMerge.replaces(row("a", 100, deleted = true), a))
        assertFalse("a newer live copy after a deletion wins", !RowMerge.replaces(row("a", 100, deleted = true), row("a", 101)))
    }

    // --- A row by both routes ---

    @Test
    fun theSameRowByBothRoutesIsAppliedOnce() {
        val r = row("pc-1", 100)
        val store = FakeStore()
        both(store, FakeCursors(), FakeRemote("server", r), FakeRemote("direct", r))
        assertEquals(1, store.remote.size)
        assertEquals("written once, not once per route", 1, store.stores)
        assertEquals(RowMerge.ROUTE_SERVER or RowMerge.ROUTE_DIRECT, store.remote.getValue("pc-1").second)
    }

    @Test
    fun overlappingAndConflictingRowsGiveTheSameResultInEitherOrder() {
        // A: the laptop has a newer edit; B: the server has a newer deletion; C: the laptop's deletion is older than
        // the server's edit; D: the same updated_at, deleted on one side; E, F: only on one route.
        val serverRows = arrayOf(row("A", 100, end = 2_000), row("B", 300, deleted = true), row("C", 250, end = 2_500),
            row("D", 400), row("E", 100))
        val laptopRows = arrayOf(row("A", 200, end = 2_200), row("B", 200, end = 2_300), row("C", 150, deleted = true),
            row("D", 400, deleted = true), row("F", 100))
        val results = listOf(listOf("direct", "server"), listOf("server", "direct")).map { order ->
            val store = FakeStore()
            val server = FakeRemote("server", *serverRows)
            val laptop = FakeRemote("direct", *laptopRows)
            SyncCore(store, FakeCursors()).syncAll(order) { if (it == "server") server else laptop }
            store.remote.mapValues { it.value.first }.toSortedMap()
        }
        assertEquals("the order of the routes doesn't matter", results[0], results[1])
        val r = results[0]
        assertEquals("newer wins (the laptop's edit)", 2_200L, r.getValue("A").end)
        assertTrue("a newer deletion wins", r.getValue("B").deleted)
        assertFalse("an older deletion loses", r.getValue("C").deleted)
        assertEquals(2_500L, r.getValue("C").end)
        assertTrue("same updated_at: the deletion wins", r.getValue("D").deleted)
        assertTrue(r.containsKey("E") && r.containsKey("F"))
    }

    // --- Cursors ---

    @Test
    fun eachTransportMovesOnlyItsOwnCursor() {
        val cursors = FakeCursors()
        val server = FakeRemote("server", row("s1", 1), row("s2", 1), row("s3", 1))
        val laptop = FakeRemote("direct", row("l1", 1))
        both(FakeStore(), cursors, server, laptop, batch = 2)
        assertEquals("3", cursors.cursor("server"))
        assertEquals("1", cursors.cursor("direct"))
        assertEquals(listOf("direct=1", "server=2", "server=3"), cursors.moves)

        // New rows on the server only: the laptop's cursor stays put
        server.feed.add(row("s4", 2))
        both(FakeStore(), cursors, server, laptop)
        assertEquals("4", cursors.cursor("server"))
        assertEquals("1", cursors.cursor("direct"))
    }

    @Test
    fun anUnknownCursorRestartsOnlyThatTransportAndKeepsTheRows() {
        val store = FakeStore()
        val cursors = FakeCursors()
        val server = FakeRemote("server", row("s1", 1), row("s2", 1))
        val laptop = FakeRemote("direct", row("l1", 1), row("l2", 1))
        both(store, cursors, server, laptop)
        laptop.forgetCursors = true // e.g. the laptop's feed file was deleted
        laptop.feed.add(row("l3", 2))
        both(store, cursors, server, laptop)
        assertEquals("pulled again from the start", "3", cursors.cursor("direct"))
        assertEquals("the server's cursor isn't touched", "2", cursors.cursor("server"))
        assertTrue("nothing was forgotten", listOf("s1", "s2", "l1", "l2", "l3").all { it in store.remote })
        assertEquals("rows pulled again aren't written again", 5, store.stores)
    }

    // --- One route down ---

    @Test
    fun serverUnreachableLaptopStillSyncs() {
        val store = FakeStore().apply { own.add(row("p1", 5).copy(mine = true, kind = "phone_active")) }
        val cursors = FakeCursors()
        val server = FakeRemote("server", row("s1", 1)).apply { down = true }
        val laptop = FakeRemote("direct", row("l1", 1))
        val outcomes = both(store, cursors, server, laptop)
        assertNull(outcomes.single { it.transport == "direct" }.error)
        assertTrue(outcomes.single { it.transport == "server" }.error != null)
        assertTrue("l1" in store.remote && "s1" !in store.remote)
        assertEquals("the laptop got the phone's row", listOf("p1"), laptop.received.map { it.id })
        assertEquals("still waiting for the server", listOf("p1"), store.pendingIntervals("server", 10).map { it.id })
        assertEquals("0", cursors.cursor("server"))

        server.down = false // the server is back: it catches up on its own
        both(store, cursors, server, laptop)
        assertTrue("s1" in store.remote)
        assertEquals(listOf("p1"), server.received.map { it.id })
        assertEquals("sent once to the laptop", 1, laptop.received.size)
    }

    @Test
    fun laptopUnreachableServerStillSyncs() {
        val store = FakeStore()
        val cursors = FakeCursors()
        val server = FakeRemote("server", row("s1", 1))
        val laptop = FakeRemote("direct", row("l1", 1)).apply { down = true }
        val outcomes = both(store, cursors, server, laptop)
        assertEquals(listOf("direct", "server"), outcomes.map { it.transport })
        assertTrue(outcomes[0].error != null)
        assertNull(outcomes[1].error)
        assertTrue("s1" in store.remote && "l1" !in store.remote)
        assertEquals("1", cursors.cursor("server"))
        assertEquals("0", cursors.cursor("direct"))
    }

    // --- Timelines ---

    @Test
    fun rowsLookTheSameInTheTimelinesWhicheverRouteBroughtThem() {
        val rows = arrayOf(row("x", 1, start = 3_600, end = 7_200), row("y", 1, start = 7_000, end = 9_000),
            row("z", 1, start = 20_000, end = 21_000, deleted = true))
        val viaServer = FakeStore().also { both(it, FakeCursors(), FakeRemote("server", *rows), FakeRemote("direct")) }
        val viaLaptop = FakeStore().also { both(it, FakeCursors(), FakeRemote("server"), FakeRemote("direct", *rows)) }
        val viaBoth = FakeStore().also { both(it, FakeCursors(), FakeRemote("server", *rows), FakeRemote("direct", *rows)) }
        val spans = listOf(viaServer, viaLaptop, viaBoth).map { Timelines.cut(it.remote.values.map { r -> r.first }, 0, 86_400) }
        assertEquals(spans[0], spans[1])
        assertEquals(spans[0], spans[2])
        assertEquals(listOf(Span(3_600, 9_000)), spans[0]) // merged, deleted row left out
    }
}
