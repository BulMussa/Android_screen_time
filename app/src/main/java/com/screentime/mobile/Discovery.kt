package com.screentime.mobile

import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/*
 * Finding the paired laptop on the network. No Android classes here, so plain JVM unit tests cover the
 * order and the timeouts with fake sources; the real sources are in DirectTransport.kt.
 */

/** A laptop address and its HTTPS port. */
data class Endpoint(val host: String, val port: Int) {
    override fun toString(): String = if (':' in host) "[$host]:$port" else "$host:$port"

    companion object {
        /** "192.168.1.20", "192.168.1.20:47613", "fe80::1" or "[fe80::1]:47613"; null if it isn't one. */
        fun parse(text: String, defaultPort: Int): Endpoint? {
            val t = text.trim()
            val (host, port) = when {
                t.startsWith("[") && "]" in t -> {
                    val host = t.substring(1, t.indexOf(']'))
                    val rest = t.substring(t.indexOf(']') + 1)
                    host to if (rest.isEmpty()) defaultPort else rest.removePrefix(":").toIntOrNull()
                }
                t.count { it == ':' } == 1 -> t.substringBefore(':') to t.substringAfter(':').toIntOrNull()
                else -> t to defaultPort // an IPv4 address, or IPv6 without a port
            }
            if (port == null || port !in 1..65535 || !PeerCrypto.isIpLiteral(host)) return null
            return Endpoint(host, port)
        }
    }
}

/** The ways of finding the laptop, in the order they are tried. [label] goes into the sync status. */
enum class DiscoveryStep(val label: String) {
    LAST_KNOWN("last known address"),
    BROADCAST("broadcast"),
    GATEWAY("Wi-Fi gateway"),
    MANUAL("address in Settings"),
}

enum class ProbeResult { OK, UNREACHABLE, WRONG_CERTIFICATE }

data class Found(val endpoint: Endpoint, val step: DiscoveryStep)

/** Nothing answered. [wrongCertificate]: something answered with another certificate than the paired one. */
class DiscoveryException(val tried: List<DiscoveryStep>, val wrongCertificate: Boolean) :
    Exception("tried " + tried.joinToString(", ") { it.label })

/** Where Discovery looks. Each call may block; Discovery caps it with its own timeout anyway. */
interface DiscoverySources {
    /** Whether the laptop with the pinned certificate answers at [endpoint]. */
    fun probe(endpoint: Endpoint, timeoutMs: Int): ProbeResult

    /** Asks the network "who is <laptop>?" and collects the answers for [timeoutMs]. */
    fun broadcast(timeoutMs: Int): List<Endpoint>

    /** The Wi-Fi network's gateway address (the laptop, when the phone uses the laptop's hotspot), or null. */
    fun gateway(): String?
}

/**
 * Tries, in order: (1) the last known addresses, (2) a UDP broadcast, (3) the Wi-Fi gateway, (4) the
 * address typed in Settings, and stops at the first that answers with the pinned certificate. Every
 * call to a source runs on a worker thread with a short hard timeout, so a hanging network call can't
 * hold up a sync, and [find] refuses to run on the main thread.
 */
class Discovery(
    private val sources: DiscoverySources,
    private val timeouts: Timeouts = Timeouts(),
    private val isMainThread: () -> Boolean = { false },
) {
    data class Timeouts(
        val probeMs: Int = 1_500, // a laptop on the same network answers well within this
        val broadcastMs: Int = 1_500,
        val gatewayMs: Int = 500,
        val graceMs: Int = 250, // on top of each, before the call is given up on
        val maxPerStep: Int = 3, // addresses probed per step at most
    )

    /**
     * [lastKnown]: the address that answered last, then the ones from the pairing code. [port] is the
     * laptop's port for the gateway. [canBroadcast]: whether the laptop's id is known yet (it comes with
     * the pairing's answer). Throws [DiscoveryException] if nothing answered.
     */
    fun find(lastKnown: List<Endpoint>, port: Int, manual: Endpoint?, canBroadcast: Boolean): Found {
        check(!isMainThread()) { "discovery does network calls; run it off the main thread" }
        val tried = LinkedHashSet<Endpoint>()
        val steps = ArrayList<DiscoveryStep>()
        var wrongCertificate = false

        fun tryAll(step: DiscoveryStep, candidates: List<Endpoint>): Found? {
            steps.add(step)
            for (endpoint in candidates.filter { it !in tried }.distinct().take(timeouts.maxPerStep)) {
                tried.add(endpoint)
                when (timed(timeouts.probeMs, ProbeResult.UNREACHABLE) { sources.probe(endpoint, timeouts.probeMs) }) {
                    ProbeResult.OK -> return Found(endpoint, step)
                    ProbeResult.WRONG_CERTIFICATE -> wrongCertificate = true
                    ProbeResult.UNREACHABLE -> {}
                }
            }
            return null
        }

        tryAll(DiscoveryStep.LAST_KNOWN, lastKnown)?.let { return it }
        if (canBroadcast) {
            val answers = timed(timeouts.broadcastMs, emptyList()) { sources.broadcast(timeouts.broadcastMs) }
            tryAll(DiscoveryStep.BROADCAST, answers)?.let { return it }
        }
        val gateway = timed(timeouts.gatewayMs, null) { sources.gateway() }
        if (gateway != null && PeerCrypto.isIpLiteral(gateway)) {
            tryAll(DiscoveryStep.GATEWAY, listOf(Endpoint(gateway, port)))?.let { return it }
        }
        if (manual != null) tryAll(DiscoveryStep.MANUAL, listOf(manual))?.let { return it }
        throw DiscoveryException(steps, wrongCertificate)
    }

    /** [block] on a worker thread; [fallback] if it fails or takes longer than [ms] (plus the grace). */
    private fun <T> timed(ms: Int, fallback: T, block: () -> T): T {
        val future: Future<T> = WORKERS.submit<T> { block() }
        return try {
            future.get((ms + timeouts.graceMs).toLong(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            fallback
        } catch (e: ExecutionException) {
            fallback
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            fallback
        }
    }

    private companion object {
        /** Daemon threads, made as needed; a call that ignores its timeout can't keep the app alive. */
        val WORKERS = Executors.newCachedThreadPool { r -> Thread(r, "discovery").apply { isDaemon = true } }
    }
}

/** The UDP discovery datagrams (the Windows app's peer.py answers them). */
object DiscoveryMessages {
    const val PORT = 47614

    /** "Who is [laptopId]?", asked by this phone ([phoneId], which the laptop checks against the paired phone). */
    fun request(phoneId: String, laptopId: String): ByteArray = JSONObject()
        .put("type", "screentime-discover")
        .put("v", 1)
        .put("from", phoneId)
        .put("want", laptopId)
        .toString().toByteArray(Charsets.UTF_8)

    /**
     * The laptop's answer: {"type": "screentime-here", "v": 1, "device_id", "address", "port"}. Only an
     * answer from [laptopId] counts. Uses the address it names, or the one it came from if that isn't an
     * IP address. Null if it isn't such an answer.
     */
    fun parseReply(data: ByteArray, sourceIp: String, laptopId: String): Endpoint? = try {
        val o = JSONObject(String(data, Charsets.UTF_8))
        if (o.optString("type") != "screentime-here" || o.optInt("v") != 1 || o.optString("device_id") != laptopId) {
            null
        } else {
            val port = o.optInt("port", -1)
            val named = o.optString("address")
            val host = if (PeerCrypto.isIpLiteral(named)) named else sourceIp
            if (port in 1..65535 && PeerCrypto.isIpLiteral(host)) Endpoint(host, port) else null
        }
    } catch (e: JSONException) {
        null
    }
}
