package com.screentime.mobile

/**
 * Sends what changed on this phone and fetches what other devices sent, over each transport the
 * sync mode uses: your own server, and "direct", the paired laptop on your own network.
 */
object SyncEngine {
    private const val BATCH = 1000

    const val DEFAULT_MODE = "server"
    val TRANSPORTS = listOf("server", "direct")
    /** Sync mode -> the transports it uses, in order: with both, the laptop first, then the server. */
    val MODES = mapOf("server" to listOf("server"), "direct" to listOf("direct"), "both" to listOf("direct", "server"))

    /** One sync, connect, disconnect or backup import at a time. */
    val lock = Any()

    fun usesServer(mode: String): Boolean = "server" in MODES[mode].orEmpty()

    /** The transport called [name], ready to use; throws [SyncException] if it can't be used now. */
    fun transportFor(name: String): Transport = when (name) {
        "server" -> {
            val server = Graph.prefs.server
            val token = Graph.prefs.token
            if (server == null || token == null) throw NotSetUpException("Not connected")
            ServerTransport(server, token)
        }
        "direct" -> DirectTransport(Graph.prefs.peer ?: throw NotSetUpException("Not paired with a laptop"), Graph.appContext)
        else -> throw IllegalArgumentException("unknown transport $name")
    }

    /** Syncs over each transport of the sync mode; one failing doesn't stop the others. */
    fun run(trigger: Trigger): List<Outcome> = synchronized(lock) {
        MODES.getValue(Graph.prefs.syncMode).map { name ->
            try {
                if (name == "direct") checkDirect(trigger)
                val transport = transportFor(name)
                val rejected = push(transport)
                pull(transport)
                if (name == "direct") Graph.prefs.directMisses = 0
                Outcome(name, rejected, null, transport.note)
            } catch (e: SyncException) {
                if (name == "direct" && e is LaptopNotFoundException) Graph.prefs.directMisses += 1
                Outcome(name, 0, e, quiet = name == "direct" && DirectPolicy.quiet(trigger, e))
            }
        }
    }

    /**
     * Before trying the laptop: local network access (Android 17, targeting 37+), Wi-Fi, and in the
     * background a pause after repeated misses. Throws the reason not to try.
     */
    private fun checkDirect(trigger: Trigger) {
        val prefs = Graph.prefs
        if (prefs.peer == null) return // transportFor says "Not paired with a laptop"
        if (LocalNetworkAccess.missing(Graph.appContext)) throw LocalNetworkException()
        val now = System.currentTimeMillis()
        when (DirectPolicy.decide(trigger, Net.onLocalNetwork(Graph.appContext), prefs.directMisses, prefs.directLastAttemptMs, now)) {
            DirectPolicy.Decision.SKIP_NO_WIFI -> throw SkippedException("not on Wi-Fi")
            DirectPolicy.Decision.SKIP_BACKING_OFF -> throw SkippedException("backing off after ${prefs.directMisses} misses")
            DirectPolicy.Decision.TRY -> {}
        }
        if (trigger == Trigger.BACKGROUND) prefs.directLastAttemptMs = now
        if (!Net.onLocalNetwork(Graph.appContext)) throw NoWifiException() // Sync now or opening the app: say so
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
