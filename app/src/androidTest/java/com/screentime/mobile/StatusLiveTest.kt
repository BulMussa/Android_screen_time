package com.screentime.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalTime

/**
 * The status texts for a wrong pairing secret and a wrong key, from the real DirectTransport against a scratch
 * copy of the laptop's listener (reached through `adb reverse`). Skipped unless the scratch listener is named:
 *
 *   adb shell am instrument -w -e class com.screentime.mobile.StatusLiveTest -e scratchPort 47699
 *     -e scratchPin <sha256 hex> -e scratchSecret <pairing secret> com.screentime.mobile.test/androidx.test.runner.AndroidJUnitRunner
 *
 * The pairings here live only in memory: DirectTransport saves a pairing only over the saved one with the same
 * certificate, and the scratch listener's certificate is its own, so the phone's real pairing isn't touched.
 */
@RunWith(AndroidJUnit4::class)
class StatusLiveTest {
    private lateinit var scratch: PeerPairing
    private val now = LocalTime.of(12, 30)

    @Before
    fun setUp() {
        val args = InstrumentationRegistry.getArguments()
        val port = args.getString("scratchPort")
        assumeTrue("pass -e scratchPort … to run against a scratch listener", port != null)
        val pin = PeerCrypto.normalizePin(args.getString("scratchPin") ?: "") ?: error("pass -e scratchPin")
        val secret = args.getString("scratchSecret") ?: error("pass -e scratchSecret")
        check(Graph.prefs.peer?.certSha256 != pin) { "the scratch listener must not be the phone's real laptop" }
        scratch = PeerPairing("Scratch laptop", listOf("127.0.0.1"), port!!.toInt(), pin, secret)
    }

    private fun statusOf(block: () -> Unit): String = try {
        block()
        fail("expected the laptop to refuse")
        ""
    } catch (e: SyncException) {
        SyncStatus.text(listOf(Outcome("direct", 0, e)), previous = "", now = now)
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun wrongSecretThenWrongKey() {
        // Wrong pairing secret
        val wrongSecret = scratch.copy(secret = scratch.secret + "x")
        assertEquals(
            "The laptop didn't accept the pairing code: it's wrong, expired or already used. Pair again with a new code.",
            statusOf { DirectTransport(wrongSecret, context).pair() },
        )
        // The right secret pairs; a wrong key is then refused
        val transport = DirectTransport(scratch, context)
        transport.pair()
        val wrongKey = scratch.copy(secret = null, key = "not-the-key", deviceId = null)
        assertEquals(
            "The laptop doesn't accept this phone anymore (the pairing was removed or replaced there). Pair again.",
            statusOf { DirectTransport(wrongKey, context).send(emptyList(), emptyList()) },
        )
    }

    @Test
    fun laptopOff() {
        // Nothing listens on this port: not found at the last known address, no broadcast (the laptop's id
        // isn't known), not at the gateway.
        val off = scratch.copy(port = scratch.port + 1, key = "k", secret = null)
        assertEquals(
            "Laptop not found. Is it on, with Screen Time running and syncing with the phone, on the same Wi-Fi? Will try again.",
            statusOf { DirectTransport(off, context).send(emptyList(), emptyList()) },
        )
    }
}
