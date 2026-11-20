package com.screentime.mobile

/**
 * Sends what changed on this phone and fetches what other devices sent, over each transport the
 * sync mode uses. Today the only working transport is your own server; "direct" (this phone talking
 * to the PC on your own network) is a placeholder, like in the Windows app.
 */
object SyncEngine {
    private const val BATCH = 1000

    const val DEFAULT_MODE = "server"
    val TRANSPORTS = listOf("server", "direct")
    /** Sync mode -> the transports it uses. */
    val MODES = mapOf("server" to listOf("server"), "direct" to listOf("direct"), "both" to listOf("server", "direct"))
    const val DIRECT_NOT_READY = "Direct sync isn't available yet. Choose Server or Both to sync."

    /** One sync, connect, disconnect or backup import at a time. */
    val lock = Any()

    fun usesServer(mode: String): Boolean = "server" in MODES[mode].orEmpty()

    /** The transport called [name], ready to use; throws [SyncException] if it can't be used now. */
    fun transportFor(name: String): Transport = when (name) {
        "server" -> {
            val server = Graph.prefs.server
            val token = Graph.prefs.token
            if (server == null || token == null) throw SyncException("Not connected")
            ServerTransport(server, token)
        }
        "direct" -> throw SyncException(DIRECT_NOT_READY) // the phone will talk to the PC directly; not built yet
        else -> throw IllegalArgumentException("unknown transport $name")
    }

    /** Syncs over every transport of the sync mode. Returns how many rows were rejected; throws [SyncException]. */
    fun run(): Int = synchronized(lock) {
        // Direct isn't built yet, so "both" works like "server" for now.
        val names = MODES.getValue(Graph.prefs.syncMode).filter { it != "direct" }
        if (names.isEmpty()) throw SyncException(DIRECT_NOT_READY)
        names.sumOf { name ->
            val transport = transportFor(name)
            val rejected = push(transport)
            pull(transport)
            rejected
        }
    }

    /** Forgets what came from other devices; every transport then pulls from the start. */
    fun resetRemote() {
        Graph.db.deleteRemote()
        for (name in TRANSPORTS) Graph.prefs.setCursor(name, "0")
    }

    private fun push(transport: Transport): Int {
        val db = Graph.db
        var rejectedCount = 0
        while (true) {
            val intervals = db.pendingIntervals(transport.name, BATCH)
            val usage = db.pendingUsage(transport.name, BATCH)
            if (intervals.isEmpty() && usage.isEmpty()) return rejectedCount
            val bad = transport.send(intervals, usage)
            val badIds = bad.intervals.map { it.id }.toSet()
            val badUsage = bad.usage.toSet()
            db.markSent(transport.name, intervals.filter { it.id !in badIds }, usage.filter { it !in badUsage })
            rejectedCount += bad.count
            // Rejected rows stay unsent; stop here, or the next round would send them again forever.
            if (bad.count > 0) return rejectedCount
        }
    }

    private fun pull(transport: Transport) {
        val prefs = Graph.prefs
        var restarted = false
        while (true) {
            val cursor = prefs.cursor(transport.name)
            val page = try {
                transport.fetch(cursor, BATCH)
            } catch (e: CursorUnknownException) {
                if (cursor != "0" && !restarted) {
                    resetRemote()
                    restarted = true
                    continue
                }
                throw e
            }
            Graph.db.applyRemote(page.intervals.map { if (it.source.isEmpty()) it.copy(source = "unknown") else it }, transport.name)
            prefs.setCursor(transport.name, page.cursor) // moved only after the page is saved
            if (!page.hasMore) return
        }
    }
}
