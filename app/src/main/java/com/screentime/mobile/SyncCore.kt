package com.screentime.mobile

/*
 * Push and pull over the transports, and the rule for merging what comes back. No Android classes:
 * the database and the cursors are interfaces, so unit tests run the real engine with a fake server
 * and a fake laptop.
 */

/** How a row from another device is merged. The same rule on the phone and on the PC, for both routes. */
object RowMerge {
    const val ROUTE_SERVER = 1
    const val ROUTE_DIRECT = 2

    /** The route bit of a transport ("server" or "direct"). */
    fun route(transport: String): Int = when (transport) {
        "server" -> ROUTE_SERVER
        "direct" -> ROUTE_DIRECT
        else -> 0
    }

    /**
     * Whether [incoming] replaces [old]: the newer updated_at wins; on the same updated_at a deletion wins
     * over a live copy, so the result doesn't depend on which route was heard first; otherwise ours stays
     * (the same row arriving by both routes is applied once).
     */
    fun replaces(old: Interval?, incoming: Interval): Boolean = when {
        old == null -> true
        incoming.updatedMs > old.updatedMs -> true
        incoming.updatedMs < old.updatedMs -> false
        else -> incoming.deleted && !old.deleted
    }
}

/** Where synced rows live (the database). */
interface SyncStore {
    fun pendingIntervals(transport: String, limit: Int): List<Interval>
    fun pendingUsage(transport: String, limit: Int): List<Usage>
    fun markSent(transport: String, intervals: List<Interval>, usage: List<Usage>)

    /** Merges rows fetched from [transport] with [RowMerge] and remembers that route has them. Returns how many were stored. */
    fun applyRemote(rows: List<Interval>, transport: String): Int
}

/** Each transport's pull cursor, kept apart: one transport's cursor never moves another's. */
interface CursorStore {
    fun cursor(transport: String): String
    fun setCursor(transport: String, value: String)
}

class SyncCore(private val store: SyncStore, private val cursors: CursorStore, private val batch: Int = 1000) {
    /** Each transport on its own: one failing (unreachable, refused) neither stops nor touches the others. */
    fun syncAll(names: List<String>, open: (String) -> Transport): List<Outcome> = names.map { name ->
        try {
            val transport = open(name)
            val rejected = sync(transport)
            Outcome(name, rejected, null, transport.note)
        } catch (e: SyncException) {
            Outcome(name, 0, e)
        }
    }

    /** Push, then pull, over one transport. Returns how many rows it rejected. */
    fun sync(transport: Transport): Int {
        val rejected = push(transport)
        pull(transport)
        return rejected
    }

    private fun push(transport: Transport): Int {
        var rejectedCount = 0
        while (true) {
            val intervals = store.pendingIntervals(transport.name, batch)
            val usage = store.pendingUsage(transport.name, batch)
            if (intervals.isEmpty() && usage.isEmpty()) return rejectedCount
            val bad = transport.send(intervals, usage)
            val badIds = bad.intervals.map { it.id }.toSet()
            val badUsage = bad.usage.toSet()
            store.markSent(transport.name, intervals.filter { it.id !in badIds }, usage.filter { it !in badUsage })
            rejectedCount += bad.count
            // Rejected rows stay unsent; stop here, or the next round would send them again forever.
            if (bad.count > 0) return rejectedCount
        }
    }

    private fun pull(transport: Transport) {
        var restarted = false
        while (true) {
            val cursor = cursors.cursor(transport.name)
            val page = try {
                transport.fetch(cursor, batch)
            } catch (e: CursorUnknownException) {
                // The other side doesn't know our cursor (e.g. it was restored from an older backup): pull this
                // transport from the start. Only its cursor moves; rows stay, and the merge keeps the newer copies.
                if (cursor != "0" && !restarted) {
                    cursors.setCursor(transport.name, "0")
                    restarted = true
                    continue
                }
                throw e
            }
            store.applyRemote(page.intervals.map { if (it.source.isEmpty()) it.copy(source = "unknown") else it }, transport.name)
            cursors.setCursor(transport.name, page.cursor) // moved only after the page is saved
            if (!page.hasMore) return
        }
    }
}
