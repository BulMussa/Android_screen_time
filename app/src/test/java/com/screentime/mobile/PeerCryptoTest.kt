package com.screentime.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * The certificate pin and the request proof. The expected values come from the Windows app's own code
 * (screentime/tlscert.py made the certificate, peer.request_mac the proofs), so these tests show that
 * both sides agree.
 */
class PeerCryptoTest {
    /** A throwaway self-signed P-256 certificate from tlscert.make_certificate (its key was discarded). */
    private val certPem = """
        -----BEGIN CERTIFICATE-----
        MIIBnTCCAUKgAwIBAgIUKE5QCP36QvTczGpkMX5zTla7TrkwCgYIKoZIzj0EAwIw
        IDEeMBwGA1UEAwwVU2NyZWVuIFRpbWUgdW5pdCB0ZXN0MB4XDTI2MTExOTA2NTky
        MVoXDTQ2MTExNTA2NTkyMVowIDEeMBwGA1UEAwwVU2NyZWVuIFRpbWUgdW5pdCB0
        ZXN0MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEPjbA4i4MD4TpCeD3BgDGT2vi
        RnaPjKZkuYkeBWFRTaS7a6NeJ630cVEMicOj5NQTJxHB99snAeMCId1P3SdnVKNa
        MFgwEgYDVR0TAQH/BAgwBgEB/wIBADAOBgNVHQ8BAf8EBAMCAoQwEwYDVR0lBAww
        CgYIKwYBBQUHAwEwHQYDVR0OBBYEFJjICHb+KgbwrJR6i4dm4Bs/Mm2vMAoGCCqG
        SM49BAMCA0kAMEYCIQD51/1Jj7DUE9gXxit1F69BDkMeB5I36mdpjiK30PLjNwIh
        AP06IpwGvUNC8+iG455HQFiNtVopnVOvVwJG0ZzBZREZ
        -----END CERTIFICATE-----
    """.trimIndent()

    /** tlscert.sha256 of that certificate. */
    private val pin = "e6d52af88866d80bcc5ae4d76a60f5ea1a11b6a254af23b30fdee76a53f6a492"

    private val cert: X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(certPem.byteInputStream()) as X509Certificate

    // --- Fingerprint ---

    @Test
    fun fingerprintIsSha256OfTheDerBytes() {
        assertEquals(pin, PeerCrypto.sha256Hex(cert.encoded))
        assertTrue(PeerCrypto.pinMatches(cert.encoded, pin))
    }

    @Test
    fun pinIsAcceptedInEitherWriting() {
        val colons = pin.uppercase().chunked(2).joinToString(":") // as the laptop shows it to people
        assertEquals(pin, PeerCrypto.normalizePin(colons))
        assertEquals(pin, PeerCrypto.normalizePin(" ${pin.uppercase()} "))
        assertTrue(PeerCrypto.pinMatches(cert.encoded, colons))
    }

    @Test
    fun badPinsAreRefused() {
        assertNull(PeerCrypto.normalizePin(pin.dropLast(1))) // 63 digits
        assertNull(PeerCrypto.normalizePin(pin.dropLast(1) + "g")) // not hex
        assertNull(PeerCrypto.normalizePin(""))
        assertFalse(PeerCrypto.pinMatches(cert.encoded, "not a pin"))
        val otherPin = pin.dropLast(1) + if (pin.last() == '0') "1" else "0"
        assertFalse(PeerCrypto.pinMatches(cert.encoded, otherPin))
    }

    @Test
    fun trustManagerAcceptsOnlyThePinnedCertificate() {
        PinnedTrustManager(pin).checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA") // no exception
        val other = PinnedTrustManager(pin.dropLast(1) + if (pin.last() == '0') "1" else "0")
        assertThrows(PinMismatchException::class.java) { other.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA") }
        assertThrows(PinMismatchException::class.java) { PinnedTrustManager(pin).checkServerTrusted(emptyArray(), "ECDHE_ECDSA") }
        assertThrows(PinMismatchException::class.java) { PinnedTrustManager(pin).checkServerTrusted(null, "ECDHE_ECDSA") }
        assertThrows(CertificateException::class.java) { PinnedTrustManager(pin).checkClientTrusted(arrayOf(cert), "ECDHE_ECDSA") }
        assertEquals(0, PinnedTrustManager(pin).acceptedIssuers.size)
    }

    // --- HMAC proof ---

    @Test
    fun proofMatchesTheWindowsAppForAGet() {
        // peer.request_mac("k3y-Ünïcode", "GET", "/peer/pull?since=0&limit=1000", "nonce-abc")
        assertEquals(
            "2664a3bdabbb7e42a6f600533d30e306c86f7042a625062e5f1385ccec75a51d",
            PeerCrypto.requestMac("k3y-Ünïcode", "GET", "/peer/pull?since=0&limit=1000", "nonce-abc"),
        )
    }

    @Test
    fun proofMatchesTheWindowsAppForAPostWithBody() {
        // peer.request_mac("pairing-secret", "POST", "/peer/pair", "n0nce", b'{"device_id":"d","name":"Pixel"}')
        val body = """{"device_id":"d","name":"Pixel"}""".toByteArray(Charsets.UTF_8)
        assertEquals(
            "f96e3a0240c02651008804c929b00a447fbd6389418c9cbb6fb26a921bbd9cca",
            PeerCrypto.requestMac("pairing-secret", "POST", "/peer/pair", "n0nce", body),
        )
    }

    @Test
    fun proofCoversEveryPart() {
        val base = PeerCrypto.requestMac("key", "GET", "/peer/pull?since=0&limit=1000", "n", ByteArray(0))
        assertEquals(64, base.length)
        assertTrue(base.all { it in '0'..'9' || it in 'a'..'f' })
        val changed = listOf(
            PeerCrypto.requestMac("key2", "GET", "/peer/pull?since=0&limit=1000", "n"),
            PeerCrypto.requestMac("key", "POST", "/peer/pull?since=0&limit=1000", "n"),
            PeerCrypto.requestMac("key", "GET", "/peer/pull?since=1&limit=1000", "n"),
            PeerCrypto.requestMac("key", "GET", "/peer/pull?since=0&limit=1000", "m"),
            PeerCrypto.requestMac("key", "GET", "/peer/pull?since=0&limit=1000", "n", byteArrayOf(0)),
        )
        changed.forEach { assertFalse(it == base) }
    }

    @Test
    fun emptyBodyHashIsTheSha256OfNothing() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", PeerCrypto.sha256Hex(ByteArray(0)))
    }

    // --- The pairing code ---

    @Test
    fun qrCodeIsParsed() {
        val code = """{"name":"DESKTOP-1","addresses":["192.168.1.20","fe80::1"],"port":47613,"secret":"s3cr3t","cert_sha256":"$pin"}"""
        val p = PairingCode.parse(code)
        assertEquals("DESKTOP-1", p.name)
        assertEquals(listOf("192.168.1.20", "fe80::1"), p.addresses)
        assertEquals(47613, p.port)
        assertEquals("s3cr3t", p.secret)
        assertEquals(pin, p.certSha256)
        assertNull(p.key)
        assertEquals(p, PeerPairing.fromJson(p.toJson())) // as kept in the encrypted preferences
    }

    @Test
    fun badCodesAreRefused() {
        val ok = """"port":47613,"secret":"s","cert_sha256":"$pin""""
        listOf(
            "not json",
            """{"hello":"world"}""",
            """{"addresses":[],$ok}""",
            """{"addresses":["laptop.example.com"],$ok}""", // a name would need DNS
            """{"addresses":["192.168.1.256"],$ok}""",
            """{"addresses":["192.168.1.20"],"port":70000,"secret":"s","cert_sha256":"$pin"}""",
            """{"addresses":["192.168.1.20"],"port":47613,"secret":"","cert_sha256":"$pin"}""",
            """{"addresses":["192.168.1.20"],"port":47613,"secret":"s","cert_sha256":"abc"}""",
        ).forEach { assertThrows(it, PairingCodeException::class.java) { PairingCode.parse(it) } }
    }

    @Test
    fun manualEntryIsChecked() {
        val p = PairingCode.manual(" 192.168.1.20, 10.0.0.5 ", "", " s3cr3t ", pin.uppercase().chunked(2).joinToString(":"))
        assertEquals(listOf("192.168.1.20", "10.0.0.5"), p.addresses)
        assertEquals(PairingCode.DEFAULT_PORT, p.port)
        assertEquals("s3cr3t", p.secret)
        assertEquals(pin, p.certSha256)
        assertThrows(PairingCodeException::class.java) { PairingCode.manual("192.168.1.20", "port", "s", pin) }
        assertThrows(PairingCodeException::class.java) { PairingCode.manual("", "47613", "s", pin) }
    }
}
