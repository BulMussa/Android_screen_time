package com.screentime.mobile

import java.time.LocalTime
import java.time.format.DateTimeFormatter

/*
 * When to sync with the laptop, and what the status line says. No Android classes, so plain JVM unit
 * tests cover every case.
 */

/** What started a sync. Only [BACKGROUND] is quiet about a laptop that isn't there. */
enum class Trigger { MANUAL, OPEN, BACKGROUND }

/** The phone isn't on Wi-Fi (or Ethernet), so the laptop on your own network can't be reached. */
class NoWifiException : SyncException("Not on Wi-Fi, so the laptop can't be reached. It syncs again when the phone is on Wi-Fi.")

/** Discovery found no laptop at any address. */
class LaptopNotFoundException : SyncException(
    "Laptop not found. Is it on, with Screen Time running and Allow phone sync ticked, on the same Wi-Fi? Will try again.",
)

/** The laptop refused the proof (401). [pairing]: it was the pairing code's secret, else this phone's key. */
class PeerAuthException(val pairing: Boolean) : SyncException(
    if (pairing) {
        "The laptop didn't accept the pairing code: it's wrong, expired or already used. Pair again with a new code."
    } else {
        "The laptop doesn't accept this phone anymore (the pairing was removed or replaced there). Pair again."
    },
    401,
)

/** Local network access is needed (apps targeting Android 17 and later) and hasn't been allowed. */
class LocalNetworkException : SyncException(
    "Local network access is off, so the laptop can't be reached. Allow it under Settings, Laptop.",
)

/** A background attempt that was skipped on purpose (not on Wi-Fi, or backing off); never shown. */
class SkippedException(why: String) : SyncException(why)

object DirectPolicy {
    const val PERIOD_MS = 15 * 60_000L // the background job's period
    const val MAX_WAIT_MS = 4 * 60 * 60_000L

    enum class Decision { TRY, SKIP_NO_WIFI, SKIP_BACKING_OFF }

    /**
     * How long to wait after the last background attempt, given [misses] in a row (laptop not found):
     * nothing after one miss, then twice the period, doubling up to 4 hours.
     */
    fun waitAfter(misses: Int): Long =
        if (misses < 2) 0L else minOf(PERIOD_MS shl minOf(misses - 1, 10), MAX_WAIT_MS)

    /** Whether to try the laptop now. Sync now and opening the app always try, so their status is current. */
    fun decide(trigger: Trigger, onWifi: Boolean, misses: Int, lastAttemptMs: Long, nowMs: Long): Decision = when {
        trigger != Trigger.BACKGROUND -> Decision.TRY
        !onWifi -> Decision.SKIP_NO_WIFI
        nowMs - lastAttemptMs < waitAfter(misses) -> Decision.SKIP_BACKING_OFF
        else -> Decision.TRY
    }

    /** Whether a failure stays out of the status line: in the background, "not found" and skips are quiet. */
    fun quiet(trigger: Trigger, error: SyncException): Boolean =
        trigger == Trigger.BACKGROUND && (error is LaptopNotFoundException || error is NoWifiException || error is SkippedException)
}

/** Local network access, which Android 17 makes a runtime permission for apps targeting it (API 37). */
object LocalNetwork {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK" // added in API 37
    const val ENFORCED_FROM = 37

    /**
     * Whether the permission must be asked for: only on Android 17+ for an app targeting 37+. Apps targeting
     * 36 (this one, today) get local network access with INTERNET, and Google's guidance is not to declare or
     * request ACCESS_LOCAL_NETWORK before targeting 37.
     */
    fun required(sdkInt: Int, targetSdk: Int): Boolean = sdkInt >= ENFORCED_FROM && targetSdk >= ENFORCED_FROM
}

/** What one transport's sync came to: how many rows it rejected, or why it failed; [note] e.g. how the laptop was found. */
class Outcome(
    val transport: String,
    val rejected: Int,
    val error: SyncException?,
    val note: String? = null,
    val quiet: Boolean = false, // a background "laptop not found" or skip: left out of the status line
)

/** The status line for a sync. */
object SyncStatus {
    /**
     * [previous] is kept when there's nothing to say (a quiet background attempt in "Laptop directly" mode).
     * With one transport it reads as before; with both, each says its own, the laptop first.
     */
    fun text(outcomes: List<Outcome>, previous: String, now: LocalTime = LocalTime.now()): String {
        val shown = outcomes.filter { !it.quiet }
        if (shown.isEmpty()) return previous
        val time = now.format(DateTimeFormatter.ofPattern("HH:mm"))
        val rejected = shown.sumOf { it.rejected }
        val clock = if (rejected > 0) ", $rejected rows rejected (is the phone's clock right?)" else ""
        if (shown.all { it.error == null }) {
            val onlyServer = shown.all { it.transport == "server" } && outcomes.size > shown.size
            if (onlyServer) return "Synced with the server at $time$clock" // the laptop wasn't tried or found, quietly
            // Which step found the laptop, e.g. " · laptop found via broadcast (192.168.1.20)"
            val found = shown.firstOrNull { it.transport == "direct" }?.note?.let { " · laptop found $it" } ?: ""
            return "Synced at $time$clock$found"
        }
        if (shown.size == 1) return problem(shown[0].transport, shown[0].error!!)
        return shown.joinToString(" ") { o ->
            val label = if (o.transport == "server") "Server" else "Laptop"
            val e = o.error
            if (e == null) {
                "$label: synced at $time${if (o.rejected > 0) ", ${o.rejected} rows rejected" else ""}" +
                    (o.note?.let { ", found $it" } ?: "") + "."
            } else {
                "$label: ${problem(o.transport, e)}"
            }
        }
    }

    fun problem(transport: String, e: SyncException): String {
        val laptop = transport == "direct"
        return when {
            e is NotSetUpException || e is PeerTrustException || e is PeerAuthException || e is NoWifiException ||
                e is LaptopNotFoundException || e is LocalNetworkException || e is SkippedException -> e.message ?: ""
            e.status == 401 -> "The server doesn't accept this phone anymore. Disconnect and connect again."
            e.status == 429 -> "Too many requests; will try again later."
            e.status == null || e.status >= 500 -> "Offline, will try again. ${e.message}"
            laptop -> "The laptop refused the data: ${e.message}"
            else -> "The server refused the data: ${e.message}"
        }
    }
}
