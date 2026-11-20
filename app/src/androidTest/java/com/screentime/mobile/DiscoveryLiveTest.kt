package com.screentime.mobile

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real discovery sources against the real paired laptop, over the phone's Wi-Fi. Skipped unless the
 * laptop is named in the instrumentation arguments, so it never makes network calls by accident:
 *
 *   adb shell am instrument -w -e laptopIp 192.168.1.71 -e laptopPort 47613 -e pin <sha256 hex>
 *     -e laptopId <the laptop's install id> com.screentime.mobile.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Reads the phone's install id and changes nothing on the phone.
 */
@RunWith(AndroidJUnit4::class)
class DiscoveryLiveTest {
    private lateinit var laptop: Endpoint
    private lateinit var pin: String
    private lateinit var laptopId: String
    private lateinit var sources: AndroidDiscoverySources

    @Before
    fun setUp() {
        val args = InstrumentationRegistry.getArguments()
        val ip = args.getString("laptopIp")
        assumeTrue("pass -e laptopIp … to run against a real laptop", ip != null)
        laptop = Endpoint(ip!!, args.getString("laptopPort")?.toInt() ?: PairingCode.DEFAULT_PORT)
        pin = PeerCrypto.normalizePin(args.getString("pin") ?: "") ?: error("pass -e pin <sha256>")
        laptopId = args.getString("laptopId") ?: error("pass -e laptopId <id>")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        sources = AndroidDiscoverySources(context, PinnedHttps(pin), Graph.prefs.installId, laptopId)
    }

    @Test
    fun probeFindsTheLaptopWithThePinnedCertificate() {
        assertEquals(ProbeResult.OK, sources.probe(laptop, 1_500))
    }

    @Test
    fun probeRefusesAnotherCertificate() {
        val wrongPin = pin.dropLast(1) + if (pin.last() == '0') "1" else "0"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val other = AndroidDiscoverySources(context, PinnedHttps(wrongPin), Graph.prefs.installId, laptopId)
        assertEquals(ProbeResult.WRONG_CERTIFICATE, other.probe(laptop, 1_500))
    }

    @Test
    fun broadcastIsAnsweredByThePairedLaptop() {
        val started = System.nanoTime()
        val found = sources.broadcast(1_500)
        val ms = (System.nanoTime() - started) / 1_000_000
        Log.i(TAG, "broadcast answers $found in $ms ms")
        assertTrue("answers: $found", laptop in found)
        assertTrue("took $ms ms", ms <= 1_700)
    }

    @Test
    fun broadcastForAnotherLaptopGetsNoAnswer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val other = AndroidDiscoverySources(context, PinnedHttps(pin), Graph.prefs.installId, "not-this-laptop")
        assertTrue(other.broadcast(1_000).isEmpty())
    }

    @Test
    fun gatewayIsAnIpAddressOrNothing() {
        val gateway = sources.gateway()
        Log.i(TAG, "Wi-Fi gateway: $gateway")
        assertTrue("gateway $gateway", gateway == null || PeerCrypto.isIpLiteral(gateway))
    }

    @Test
    fun fullDiscoveryFallsBackToBroadcast() {
        // A last known address where nothing answers (an unused address next to the laptop's).
        val parts = laptop.host.split('.')
        val nobody = Endpoint(parts.dropLast(1).joinToString(".") + "." + (if (parts.last() == "254") "253" else "254"), laptop.port)
        val started = System.nanoTime()
        val found = Discovery(sources).find(listOf(nobody), laptop.port, null, canBroadcast = true)
        val ms = (System.nanoTime() - started) / 1_000_000
        Log.i(TAG, "found $found in $ms ms")
        assertEquals(Found(laptop, DiscoveryStep.BROADCAST), found)
    }

    private companion object {
        const val TAG = "DiscoveryLiveTest"
    }
}
