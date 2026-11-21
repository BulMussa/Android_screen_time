package com.screentime.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Looper
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.time.DateTimeException
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory

/** The laptop's certificate isn't the paired one, at every address tried. */
class PeerTrustException(message: String) : SyncException(message)

/** HTTPS that trusts exactly one certificate, the one whose SHA-256 is [pin]. */
class PinnedHttps(private val pin: String) {
    private val socketFactory: SSLSocketFactory = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(PinnedTrustManager(pin)), SecureRandom())
    }.socketFactory

    /** The pin is the identity; the laptop's self-signed certificate names no address, so names aren't checked. */
    private val anyHost = HostnameVerifier { _, session ->
        runCatching { PeerCrypto.pinMatches(session.peerCertificates.first().encoded, pin) }.getOrDefault(false)
    }

    fun open(endpoint: Endpoint, target: String, connectMs: Int, readMs: Int): HttpsURLConnection {
        val url = URL("https", if (':' in endpoint.host) "[${endpoint.host}]" else endpoint.host, endpoint.port, target)
        return (url.openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = socketFactory
            hostnameVerifier = anyHost
            instanceFollowRedirects = false
            useCaches = false
            connectTimeout = connectMs
            readTimeout = readMs
            setRequestProperty("Accept", "application/json")
        }
    }

    companion object {
        fun isPinMismatch(e: Throwable): Boolean = generateSequence(e) { it.cause }.any { it is PinMismatchException }
    }
}

/** The real places [Discovery] looks: pinned HTTPS probes, a UDP broadcast and the Wi-Fi gateway. */
class AndroidDiscoverySources(
    private val context: Context,
    private val https: PinnedHttps,
    private val phoneId: String,
    private val laptopId: String?,
) : DiscoverySources {
    /** The laptop answers GET /peer/nonce without a proof: enough to see it's there, with the pinned certificate. */
    override fun probe(endpoint: Endpoint, timeoutMs: Int): ProbeResult {
        val conn = https.open(endpoint, "/peer/nonce", timeoutMs, timeoutMs)
        return try {
            conn.requestMethod = "GET"
            when (conn.responseCode) {
                200 -> ProbeResult.OK
                429 -> ProbeResult.OK // it's there with the right certificate, just busy; the sync says so
                else -> ProbeResult.UNREACHABLE
            }
        } catch (e: IOException) {
            if (PinnedHttps.isPinMismatch(e)) ProbeResult.WRONG_CERTIFICATE else ProbeResult.UNREACHABLE
        } finally {
            conn.disconnect()
        }
    }

    override fun broadcast(timeoutMs: Int): List<Endpoint> {
        val wanted = laptopId ?: return emptyList()
        val request = DiscoveryMessages.request(phoneId, wanted)
        // The limited broadcast, and each network's own broadcast address (it also reaches a hotspot this phone shares)
        val targets = LinkedHashSet<InetAddress>()
        targets.add(InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1)))
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (a in nif.interfaceAddresses) a.broadcast?.let { targets.add(it) }
            }
        }
        val found = LinkedHashSet<Endpoint>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            fun sendAll() = targets.forEach { runCatching { socket.send(DatagramPacket(request, request.size, it, DiscoveryMessages.PORT)) } }
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            var resent = false
            sendAll()
            val buffer = ByteArray(1024)
            while (true) {
                val left = (deadline - System.nanoTime()) / 1_000_000L
                if (left <= 0) break
                if (!resent && left < timeoutMs / 2) { // datagrams get lost; ask once more halfway
                    sendAll()
                    resent = true
                }
                socket.soTimeout = minOf(left, timeoutMs / 2L).toInt().coerceAtLeast(1)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                DiscoveryMessages.parseReply(data, packet.address.hostAddress ?: "", wanted)?.let { found.add(it) }
                if (found.isNotEmpty()) break // the laptop answered; no need to wait for the rest
            }
        }
        return found.toList()
    }

    override fun gateway(): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION") // allNetworks also finds Wi-Fi without internet, which activeNetwork may skip
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val routes = cm.getLinkProperties(network)?.routes ?: continue
            val gw = routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address && !it.gateway!!.isAnyLocalAddress }?.gateway
            if (gw != null) return gw.hostAddress
        }
        return null
    }
}

/**
 * The laptop directly, over HTTPS on your own network (the Windows app's phone sync protocol, version 1).
 * Its certificate is pinned: only the one whose SHA-256 came in the pairing code is trusted. Every request
 * carries a proof (see [PeerCrypto.requestMac]); no secret or key is ever sent. The laptop is found with
 * [Discovery] once per sync.
 */
class DirectTransport(private var pairing: PeerPairing, private val context: Context) : Transport {
    override val name = "direct"

    private val https = PinnedHttps(pairing.certSha256)
    private var nextNonce: String? = null // from the last proven answer's X-Peer-Nonce, saving a round trip
    private var found: Found? = null

    /** Where the laptop was found and how, for the sync status. */
    override val note: String?
        get() = found?.let { "via ${it.step.label} (${it.endpoint.host})" }

    /** Asks the laptop for this phone's key with the pairing secret, if that hasn't happened yet. */
    fun pair() {
        if (pairing.key != null) return
        val secret = pairing.secret ?: throw NotSetUpException("Not paired with a laptop")
        val prefs = Graph.prefs
        val body = JSONObject()
            .put("device_id", prefs.installId)
            .put("name", (prefs.deviceName ?: Build.MODEL ?: "Phone").trim().ifEmpty { "Phone" }.take(100))
        val answer = request("POST", "/peer/pair", body, secret)
        try {
            if (answer.optInt("protocol", 1) != PROTOCOL) {
                throw SyncException("The laptop uses a newer version of Screen Time. Update this app.")
            }
            pairing = pairing.copy(
                key = answer.getString("key"),
                deviceId = answer.optString("device_id").ifEmpty { null },
                name = answer.optString("name").ifBlank { pairing.name }.take(100),
            )
        } catch (e: JSONException) {
            throw SyncException("Unexpected answer from the laptop")
        }
        save()
    }

    override fun send(intervals: List<Interval>, usage: List<Usage>): Rejected {
        val result = proven("POST", "/peer/push", Rows.pushBody(intervals, usage))
        return parsed { Rows.rejected(result, intervals, usage) }
    }

    override fun fetch(cursor: String, limit: Int): Page {
        val page = try {
            proven("GET", "/peer/pull?since=${URLEncoder.encode(cursor, "UTF-8")}&limit=$limit", null)
        } catch (e: SyncException) {
            if (e.status == 400) throw CursorUnknownException(e.message ?: "Unknown cursor")
            throw e
        }
        return parsed {
            // The laptop also sends its app times; this phone keeps only its own, so they are left out.
            val rows = page.getJSONArray("intervals")
            Page(
                (0 until rows.length()).map { Rows.parseInterval(rows.getJSONObject(it), mine = false) },
                page.getString("cursor"),
                page.getBoolean("has_more"),
            )
        }
    }

    /** Finds the laptop (once per sync) and remembers where it answered. */
    private fun locate(): Endpoint {
        found?.let { return it.endpoint }
        val discovery = Discovery(
            AndroidDiscoverySources(context, https, Graph.prefs.installId, pairing.deviceId),
            isMainThread = { Looper.getMainLooper().isCurrentThread },
        )
        val result = try {
            discovery.find(pairing.knownEndpoints(), pairing.port, pairing.manual, canBroadcast = pairing.deviceId != null)
        } catch (e: DiscoveryException) {
            if (e.wrongCertificate) {
                throw PeerTrustException("The laptop's certificate doesn't match the pairing, so nothing was sent. Pair again.")
            }
            throw LaptopNotFoundException()
        }
        found = result
        if (pairing.lastKnown != result.endpoint) {
            pairing = pairing.copy(lastKnown = result.endpoint)
            save()
        }
        return result.endpoint
    }

    /** A request proven with this phone's key (pairing first if needed). */
    private fun proven(method: String, target: String, body: JSONObject?): JSONObject {
        pair()
        val answer = request(method, target, body, pairing.key!!)
        if (pairing.secret != null) { // the laptop has now seen the key, so it has deleted the pairing secret
            pairing = pairing.copy(secret = null)
            save()
        }
        return answer
    }

    private fun save() = synchronized(SyncEngine.lock) {
        // Unless the pairing was removed or replaced meanwhile; keep an address typed in Settings meanwhile too
        val saved = Graph.prefs.peer
        if (saved?.certSha256 == pairing.certSha256) Graph.prefs.peer = pairing.copy(manual = saved.manual)
    }

    /**
     * One proven request. A nonce works once; a stale saved one gets one retry with a fresh nonce. A proof the
     * laptop still refuses means the pairing secret (for /peer/pair) or this phone's key is wrong: [PeerAuthException].
     */
    private fun request(method: String, target: String, body: JSONObject?, key: String): JSONObject {
        val bytes = body?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val saved = nextNonce
        nextNonce = null
        try {
            return try {
                send(method, target, bytes, key, saved ?: fetchNonce())
            } catch (e: SyncException) {
                if (saved == null || e.status != 401) throw e
                send(method, target, bytes, key, fetchNonce())
            }
        } catch (e: SyncException) {
            if (e.status == 401 && e !is PeerAuthException) throw PeerAuthException(pairing = target == "/peer/pair")
            throw e
        }
    }

    private fun fetchNonce(): String = parsed { exchange("GET", "/peer/nonce", ByteArray(0), null).getString("nonce") }

    private fun send(method: String, target: String, body: ByteArray, key: String, nonce: String): JSONObject =
        exchange(method, target, body, "HMAC $nonce:${PeerCrypto.requestMac(key, method, target, nonce, body)}")

    private fun exchange(method: String, target: String, body: ByteArray, authorization: String?): JSONObject {
        val endpoint = locate()
        val conn = https.open(endpoint, target, 5_000, 30_000)
        try {
            conn.requestMethod = method
            if (authorization != null) conn.setRequestProperty("Authorization", authorization)
            if (method == "POST") {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setFixedLengthStreamingMode(body.size) // the laptop wants a Content-Length, not chunks
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val detail = try {
                    JSONObject(text).optString("detail")
                } catch (e: JSONException) {
                    ""
                }
                throw SyncException(
                    detail.ifBlank { "The laptop answered with an error ($code)" },
                    code,
                    conn.getHeaderField("Retry-After")?.toIntOrNull(),
                )
            }
            conn.getHeaderField("X-Peer-Nonce")?.let { nextNonce = it }
            return try {
                JSONObject(text)
            } catch (e: JSONException) {
                throw SyncException("Unexpected answer from the laptop")
            }
        } catch (e: IOException) {
            if (PinnedHttps.isPinMismatch(e)) {
                throw PeerTrustException("The laptop's certificate doesn't match the pairing, so nothing was sent. Pair again.")
            }
            throw SyncException("Lost the connection to the laptop at $endpoint (${e.message})")
        } finally {
            conn.disconnect()
        }
    }

    private inline fun <T> parsed(block: () -> T): T = try {
        block()
    } catch (e: JSONException) {
        throw SyncException("Unexpected answer from the laptop")
    } catch (e: DateTimeException) {
        throw SyncException("Unexpected answer from the laptop")
    }

    companion object {
        const val PROTOCOL = 1

        /**
         * Saves a new pairing (from the QR code or typed by hand), replacing any earlier one. Everything is
         * sent to the new laptop again, and its rows are pulled from the start.
         */
        fun savePairing(pairing: PeerPairing) = synchronized(SyncEngine.lock) {
            Graph.prefs.peer = pairing
            Graph.db.forgetSent("direct")
            Graph.prefs.setCursor("direct", "0")
        }

        /** Forgets the laptop: its secret, key, pin and addresses. Rows it already sent stay on the phone. */
        fun removePairing() = synchronized(SyncEngine.lock) {
            Graph.prefs.peer = null
            Graph.db.forgetSent("direct")
            Graph.prefs.setCursor("direct", "0")
        }

        /** The address typed in Settings (null clears it); tried when nothing else finds the laptop. */
        fun setManualAddress(endpoint: Endpoint?) = synchronized(SyncEngine.lock) {
            Graph.prefs.peer?.let { Graph.prefs.peer = it.copy(manual = endpoint) }
        }
    }
}
