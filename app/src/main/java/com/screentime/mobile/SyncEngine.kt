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

    /**
     * Syncs over each transport of the sync mode (with both: the laptop, then the server). Each runs on its
     * own: one that can't be reached neither stops nor touches the other, and each keeps its own cursor.
     */
    fun run(trigger: Trigger): List<Outcome> = synchronized(lock) {
        val core = SyncCore(Graph.db, Graph.prefs, BATCH)
        core.syncAll(MODES.getValue(Graph.prefs.syncMode)) { name ->
            if (name == "direct") checkDirect(trigger)
            transportFor(name)
        }.map { o ->
            if (o.transport != "direct") return@map o
            val e = o.error
            if (e == null) Graph.prefs.directMisses = 0 else if (e is LaptopNotFoundException) Graph.prefs.directMisses += 1
            if (e == null) o else Outcome(o.transport, o.rejected, e, o.note, quiet = DirectPolicy.quiet(trigger, e))
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

    /**
     * Forgets the server's side only: the rows only it delivered, and its cursor (after connecting to a
     * server or disconnecting). What the laptop delivered, and the laptop's cursor, stay.
     */
    fun forgetServer() {
        Graph.db.forgetRoute("server")
        Graph.prefs.setCursor("server", "0")
    }
}
