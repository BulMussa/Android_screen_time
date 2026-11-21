package com.screentime.mobile

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.X509TrustManager

/*
 * Direct sync with the laptop (the Windows app's "phone sync protocol", version 1), the parts that
 * need no Android: the pairing code, the certificate pin and the request proof. Kept free of Android
 * classes so plain JVM unit tests cover them.
 */

/**
 * A pairing with the laptop. [certSha256] pins its certificate (64 lowercase hex digits). [secret] is the
 * QR code's one-time pairing secret, kept until the laptop has seen [key], the key it gave this phone.
 * [deviceId] is the laptop's install id, from its answer to the pairing. [lastKnown] is the address that
 * answered last; [manual] an address typed in Settings, tried when nothing else finds the laptop.
 */
data class PeerPairing(
    val name: String,
    val addresses: List<String>,
    val port: Int,
    val certSha256: String,
    val secret: String?,
    val key: String? = null,
    val deviceId: String? = null,
    val lastKnown: Endpoint? = null,
    val manual: Endpoint? = null,
) {
    /** Where to look first: the address that answered last, then the pairing code's. */
    fun knownEndpoints(): List<Endpoint> = (listOfNotNull(lastKnown) + addresses.map { Endpoint(it, port) }).distinct()

    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("addresses", JSONArray(addresses))
        .put("port", port)
        .put("cert_sha256", certSha256)
        .put("secret", secret ?: JSONObject.NULL)
        .put("key", key ?: JSONObject.NULL)
        .put("device_id", deviceId ?: JSONObject.NULL)
        .put("last_known", lastKnown?.toString() ?: JSONObject.NULL)
        .put("manual", manual?.toString() ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = PeerPairing(
            name = o.getString("name"),
            addresses = o.getJSONArray("addresses").let { a -> (0 until a.length()).map { a.getString(it) } },
            port = o.getInt("port"),
            certSha256 = o.getString("cert_sha256"),
            secret = o.optString("secret").takeIf { !o.isNull("secret") && it.isNotEmpty() },
            key = o.optString("key").takeIf { !o.isNull("key") && it.isNotEmpty() },
            deviceId = o.optString("device_id").takeIf { !o.isNull("device_id") && it.isNotEmpty() },
            lastKnown = if (o.isNull("last_known")) null else Endpoint.parse(o.optString("last_known"), o.getInt("port")),
            manual = if (o.isNull("manual")) null else Endpoint.parse(o.optString("manual"), o.getInt("port")),
        )
    }
}

class PairingCodeException(message: String) : Exception(message)

object PairingCode {
    const val DEFAULT_PORT = 47613

    /** The laptop's QR code: {"name", "addresses", "port", "secret", "cert_sha256"}. Throws [PairingCodeException]. */
    fun parse(text: String): PeerPairing {
        val o = try {
            JSONObject(text.trim())
        } catch (e: JSONException) {
            throw PairingCodeException("This isn't a Screen Time pairing code.")
        }
        if (!o.has("secret") || !o.has("cert_sha256")) throw PairingCodeException("This isn't a Screen Time pairing code.")
        try {
            val list = o.getJSONArray("addresses")
            return check(
                name = o.optString("name").ifBlank { "Laptop" },
                addresses = (0 until list.length()).map { list.getString(it) },
                port = o.getInt("port"),
                secret = o.getString("secret"),
                fingerprint = o.getString("cert_sha256"),
            )
        } catch (e: JSONException) {
            throw PairingCodeException("The pairing code is damaged (${e.message}).")
        }
    }

    /** The same from what was typed by hand: addresses separated by commas or spaces, the fingerprint as hex or AB:CD:… */
    fun manual(addresses: String, port: String, secret: String, fingerprint: String): PeerPairing = check(
        name = "Laptop",
        addresses = addresses.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() },
        port = port.trim().ifEmpty { DEFAULT_PORT.toString() }.toIntOrNull() ?: throw PairingCodeException("The port must be a number."),
        secret = secret.trim(),
        fingerprint = fingerprint,
    )

    private fun check(name: String, addresses: List<String>, port: Int, secret: String, fingerprint: String): PeerPairing {
        if (addresses.isEmpty()) throw PairingCodeException("The pairing code has no address of the laptop.")
        // Only IP addresses: the laptop is on your own network, and a name would need a DNS lookup.
        addresses.firstOrNull { !PeerCrypto.isIpLiteral(it) }?.let { throw PairingCodeException("\"$it\" isn't an IP address.") }
        if (port !in 1..65535) throw PairingCodeException("The port must be from 1 to 65535.")
        if (secret.isEmpty()) throw PairingCodeException("The pairing secret is missing.")
        val pin = PeerCrypto.normalizePin(fingerprint)
            ?: throw PairingCodeException("The certificate fingerprint must be 64 hex digits (SHA-256).")
        return PeerPairing(name.take(100), addresses, port, pin, secret)
    }
}

object PeerCrypto {
    fun sha256Hex(data: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(data))

    /** A fingerprint as 64 lowercase hex digits (from hex, or AB:CD:… with spaces anywhere), or null. */
    fun normalizePin(text: String): String? {
        val hexOnly = text.filterNot { it == ':' || it.isWhitespace() }.lowercase()
        return hexOnly.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    /** Whether a certificate (its DER bytes) is the pinned one. Compared in constant time. */
    fun pinMatches(certDer: ByteArray, pin: String): Boolean {
        val expected = normalizePin(pin) ?: return false
        return MessageDigest.isEqual(sha256Hex(certDer).toByteArray(), expected.toByteArray())
    }

    /**
     * The proof for one request, as the laptop checks it: hex HMAC-SHA256 with [key] (UTF-8) over
     * method \n target \n nonce \n hex SHA-256 of the body. [target] is the path with its query, exactly as sent.
     */
    fun requestMac(key: String, method: String, target: String, nonce: String, body: ByteArray = ByteArray(0)): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val message = listOf(method, target, nonce, sha256Hex(body)).joinToString("\n")
        return hex(mac.doFinal(message.toByteArray(Charsets.UTF_8)))
    }

    /** An IPv4 or IPv6 literal (never a host name, so no DNS lookup is made). */
    fun isIpLiteral(text: String): Boolean {
        val v4 = text.split('.')
        if (v4.size == 4 && v4.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() <= 255 }) return true
        return text.count { it == ':' } >= 2 && text.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

/** The laptop's certificate isn't the one the pairing pinned. */
class PinMismatchException(message: String) : CertificateException(message)

/**
 * Trusts exactly one certificate: the one whose SHA-256 is [pin]. No certificate authority is involved,
 * so the laptop's self-signed certificate works and nothing else does.
 */
class PinnedTrustManager(private val pin: String) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw PinMismatchException("the laptop sent no certificate")
        if (!PeerCrypto.pinMatches(leaf.encoded, pin)) {
            throw PinMismatchException("the laptop's certificate isn't the paired one")
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("client certificates aren't used")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
