package com.screentime.mobile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections

/** The order Discovery tries things in, and its timeouts, with fake sources (no network). */
class DiscoveryTest {
    private val last = Endpoint("192.168.1.20", 47613)
    private val fromCode = Endpoint("10.0.0.5", 47613)
    private val viaBroadcast = Endpoint("192.168.1.33", 47613)
    private val gatewayIp = "192.168.137.1"
    private val viaGateway = Endpoint(gatewayIp, 47613)
    private val manual = Endpoint("172.16.0.9", 47700)

    /** Records every call in order; [answers] says which endpoints answer and how, [hang] makes calls block. */
    private class Fake(
        val answers: Map<Endpoint, ProbeResult> = emptyMap(),
        val broadcastAnswer: List<Endpoint> = emptyList(),
        val gatewayAnswer: String? = null,
        val hang: Set<String> = emptySet(), // "probe:<endpoint>", "broadcast", "gateway"
        val fail: Set<String> = emptySet(), // the same, throwing instead
    ) : DiscoverySources {
        val calls: MutableList<String> = Collections.synchronizedList(ArrayList())
        val timeoutsSeen: MutableList<Int> = Collections.synchronizedList(ArrayList())

        private fun act(name: String) {
            calls.add(name)
            if (name in fail) throw IOException("$name failed")
            if (name in hang) Thread.sleep(10_000)
        }

        override fun probe(endpoint: Endpoint, timeoutMs: Int): ProbeResult {
            timeoutsSeen.add(timeoutMs)
            act("probe:$endpoint")
            return answers[endpoint] ?: ProbeResult.UNREACHABLE
        }

        override fun broadcast(timeoutMs: Int): List<Endpoint> {
            timeoutsSeen.add(timeoutMs)
            act("broadcast")
            return broadcastAnswer
        }

        override fun gateway(): String? {
            act("gateway")
            return gatewayAnswer
        }
    }

    private val fast = Discovery.Timeouts(probeMs = 100, broadcastMs = 100, gatewayMs = 100, graceMs = 50)

    private fun find(fake: Fake, canBroadcast: Boolean = true, manualEndpoint: Endpoint? = manual, timeouts: Discovery.Timeouts = fast) =
        Discovery(fake, timeouts).find(listOf(last, fromCode), 47613, manualEndpoint, canBroadcast)

    // --- Order ---

    @Test
    fun triesEveryStepInOrderWhenNothingAnswers() {
        val fake = Fake(broadcastAnswer = listOf(viaBroadcast), gatewayAnswer = gatewayIp)
        val e = assertThrows(DiscoveryException::class.java) { find(fake) }
        assertEquals(
            listOf("probe:$last", "probe:$fromCode", "broadcast", "probe:$viaBroadcast", "gateway", "probe:$viaGateway", "probe:$manual"),
            fake.calls,
        )
        assertEquals(DiscoveryStep.entries.toList(), e.tried)
        assertFalse(e.wrongCertificate)
    }

    @Test
    fun lastKnownAddressFirstAndNothingElse() {
        val fake = Fake(answers = mapOf(last to ProbeResult.OK), broadcastAnswer = listOf(viaBroadcast), gatewayAnswer = gatewayIp)
        assertEquals(Found(last, DiscoveryStep.LAST_KNOWN), find(fake))
        assertEquals(listOf("probe:$last"), fake.calls)
    }

    @Test
    fun pairingCodeAddressIsPartOfLastKnown() {
        val fake = Fake(answers = mapOf(fromCode to ProbeResult.OK))
        assertEquals(Found(fromCode, DiscoveryStep.LAST_KNOWN), find(fake))
        assertEquals(listOf("probe:$last", "probe:$fromCode"), fake.calls)
    }

    @Test
    fun broadcastIsSecond() {
        val fake = Fake(answers = mapOf(viaBroadcast to ProbeResult.OK), broadcastAnswer = listOf(viaBroadcast), gatewayAnswer = gatewayIp)
        assertEquals(Found(viaBroadcast, DiscoveryStep.BROADCAST), find(fake))
        assertEquals(listOf("probe:$last", "probe:$fromCode", "broadcast", "probe:$viaBroadcast"), fake.calls)
    }

    @Test
    fun broadcastAnswerIsCheckedLikeAnyAddress() {
        // Something answered the broadcast, but not with the pinned certificate: go on to the gateway.
        val fake = Fake(
            answers = mapOf(viaBroadcast to ProbeResult.WRONG_CERTIFICATE, viaGateway to ProbeResult.OK),
            broadcastAnswer = listOf(viaBroadcast), gatewayAnswer = gatewayIp,
        )
        assertEquals(Found(viaGateway, DiscoveryStep.GATEWAY), find(fake))
    }

    @Test
    fun gatewayIsThirdWithTheLaptopsPort() {
        val fake = Fake(answers = mapOf(viaGateway to ProbeResult.OK), gatewayAnswer = gatewayIp)
        assertEquals(Found(viaGateway, DiscoveryStep.GATEWAY), find(fake))
        assertEquals(listOf("probe:$last", "probe:$fromCode", "broadcast", "gateway", "probe:$viaGateway"), fake.calls)
    }

    @Test
    fun manualAddressIsLast() {
        val fake = Fake(answers = mapOf(manual to ProbeResult.OK), gatewayAnswer = gatewayIp)
        assertEquals(Found(manual, DiscoveryStep.MANUAL), find(fake))
        assertEquals("probe:$manual", fake.calls.last())
    }

    @Test
    fun noBroadcastBeforeTheLaptopsIdIsKnown() {
        val fake = Fake(answers = mapOf(manual to ProbeResult.OK), broadcastAnswer = listOf(viaBroadcast))
        find(fake, canBroadcast = false)
        assertFalse("broadcast" in fake.calls)
    }

    @Test
    fun stepsWithNothingToTryAreSkipped() {
        val fake = Fake() // no broadcast answer, no gateway, no manual address
        val e = assertThrows(DiscoveryException::class.java) { find(fake, manualEndpoint = null) }
        assertEquals(listOf(DiscoveryStep.LAST_KNOWN, DiscoveryStep.BROADCAST), e.tried)
        assertEquals(listOf("probe:$last", "probe:$fromCode", "broadcast", "gateway"), fake.calls)
    }

    @Test
    fun anAddressIsProbedOnlyOnce() {
        // The gateway and the manual address are the last known address: they aren't probed again.
        val fake = Fake(broadcastAnswer = listOf(last), gatewayAnswer = last.host)
        assertThrows(DiscoveryException::class.java) { find(fake, manualEndpoint = last) }
        assertEquals(1, fake.calls.count { it == "probe:$last" })
    }

    @Test
    fun atMostSoManyAddressesPerStep() {
        val many = (1..10).map { Endpoint("192.168.1.${100 + it}", 47613) }
        val fake = Fake(broadcastAnswer = many)
        assertThrows(DiscoveryException::class.java) { find(fake, manualEndpoint = null) }
        assertEquals(fast.maxPerStep, fake.calls.count { it.startsWith("probe:192.168.1.1") })
    }

    @Test
    fun wrongCertificateIsReported() {
        val fake = Fake(answers = mapOf(last to ProbeResult.WRONG_CERTIFICATE))
        assertTrue(assertThrows(DiscoveryException::class.java) { find(fake, manualEndpoint = null) }.wrongCertificate)
    }

    @Test
    fun aGatewayThatIsNoIpAddressIsIgnored() {
        val fake = Fake(gatewayAnswer = "router.local")
        assertThrows(DiscoveryException::class.java) { find(fake, manualEndpoint = null) }
        assertFalse(fake.calls.any { it.startsWith("probe:router") })
    }

    // --- Timeouts ---

    @Test
    fun sourcesGetTheConfiguredTimeouts() {
        val fake = Fake(broadcastAnswer = listOf(viaBroadcast))
        val timeouts = Discovery.Timeouts(probeMs = 120, broadcastMs = 340, gatewayMs = 50, graceMs = 50)
        assertThrows(DiscoveryException::class.java) { find(fake, manualEndpoint = null, timeouts = timeouts) }
        assertEquals(listOf(120, 120, 340, 120), fake.timeoutsSeen) // probe, probe, broadcast, probe
    }

    @Test
    fun defaultTimeoutsAreShort() {
        val t = Discovery.Timeouts()
        assertTrue(t.probeMs <= 2_000 && t.broadcastMs <= 2_000 && t.gatewayMs <= 1_000 && t.graceMs <= 500)
    }

    @Test
    fun aHangingProbeIsCutOffAndTheNextStepRuns() {
        val fake = Fake(answers = mapOf(viaGateway to ProbeResult.OK), gatewayAnswer = gatewayIp, hang = setOf("probe:$last"))
        val started = System.nanoTime()
        assertEquals(Found(viaGateway, DiscoveryStep.GATEWAY), find(fake))
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $ms ms; a probe should be given up after ~150 ms", ms < 2_000)
    }

    @Test
    fun aHangingBroadcastAndGatewayAreCutOff() {
        val fake = Fake(answers = mapOf(manual to ProbeResult.OK), hang = setOf("broadcast", "gateway"))
        val started = System.nanoTime()
        assertEquals(Found(manual, DiscoveryStep.MANUAL), find(fake))
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $ms ms; broadcast and gateway should be given up after ~150 ms each", ms < 2_000)
    }

    @Test
    fun everythingHangingStillEndsQuickly() {
        val fake = Fake(
            broadcastAnswer = listOf(viaBroadcast), gatewayAnswer = gatewayIp,
            hang = setOf("probe:$last", "probe:$fromCode", "broadcast", "gateway", "probe:$manual"),
        )
        val started = System.nanoTime()
        assertThrows(DiscoveryException::class.java) { find(fake) }
        val ms = (System.nanoTime() - started) / 1_000_000
        // 3 probes + broadcast + gateway, each cut off at 100 + 50 ms
        assertTrue("took $ms ms", ms < 3_000)
    }

    @Test
    fun aFailingSourceCountsAsNotFound() {
        val fake = Fake(answers = mapOf(manual to ProbeResult.OK), fail = setOf("probe:$last", "broadcast", "gateway"))
        assertEquals(Found(manual, DiscoveryStep.MANUAL), find(fake))
    }

    @Test
    fun refusesToRunOnTheMainThread() {
        val fake = Fake(answers = mapOf(last to ProbeResult.OK))
        assertThrows(IllegalStateException::class.java) {
            Discovery(fake, fast, isMainThread = { true }).find(listOf(last), 47613, null, true)
        }
        assertTrue(fake.calls.isEmpty())
    }

    // --- Datagrams and addresses ---

    @Test
    fun requestNamesThisPhoneAndTheLaptop() {
        val o = JSONObject(String(DiscoveryMessages.request("phone-1", "pc-9"), Charsets.UTF_8))
        assertEquals("screentime-discover", o.getString("type"))
        assertEquals(1, o.getInt("v"))
        assertEquals("phone-1", o.getString("from"))
        assertEquals("pc-9", o.getString("want"))
    }

    @Test
    fun replyFromTheLaptopIsRead() {
        val reply = """{"type":"screentime-here","v":1,"device_id":"pc-9","address":"192.168.1.71","port":47613}"""
        assertEquals(Endpoint("192.168.1.71", 47613), DiscoveryMessages.parseReply(reply.toByteArray(), "192.168.1.71", "pc-9"))
        val noAddress = """{"type":"screentime-here","v":1,"device_id":"pc-9","address":"","port":47613}"""
        assertEquals(Endpoint("192.168.1.71", 47613), DiscoveryMessages.parseReply(noAddress.toByteArray(), "192.168.1.71", "pc-9"))
    }

    @Test
    fun otherRepliesAreIgnored() {
        listOf(
            """{"type":"screentime-here","v":1,"device_id":"another-pc","address":"192.168.1.71","port":47613}""",
            """{"type":"screentime-here","v":2,"device_id":"pc-9","address":"192.168.1.71","port":47613}""",
            """{"type":"hello","v":1,"device_id":"pc-9","address":"192.168.1.71","port":47613}""",
            """{"type":"screentime-here","v":1,"device_id":"pc-9","address":"192.168.1.71","port":0}""",
            "not json",
        ).forEach { assertNull(it, DiscoveryMessages.parseReply(it.toByteArray(), "192.168.1.71", "pc-9")) }
    }

    @Test
    fun manualAddressesAreParsed() {
        assertEquals(Endpoint("192.168.1.20", 47613), Endpoint.parse(" 192.168.1.20 ", 47613))
        assertEquals(Endpoint("192.168.1.20", 50000), Endpoint.parse("192.168.1.20:50000", 47613))
        assertEquals(Endpoint("fe80::1", 47613), Endpoint.parse("fe80::1", 47613))
        assertEquals(Endpoint("fe80::1", 50000), Endpoint.parse("[fe80::1]:50000", 47613))
        assertEquals("[fe80::1]:50000", Endpoint("fe80::1", 50000).toString())
        listOf("laptop.local", "192.168.1.20:0", "192.168.1.20:port", "300.1.1.1", "").forEach {
            assertNull(it, Endpoint.parse(it, 47613))
        }
    }

    @Test
    fun pairingKeepsItsAddressesInTheEncryptedPreferences() {
        val p = PeerPairing("Laptop", listOf("10.0.0.5"), 47613, "a".repeat(64), null, "key", "pc-9", last, manual)
        assertEquals(p, PeerPairing.fromJson(p.toJson()))
        assertEquals(listOf(last, fromCode), p.knownEndpoints()) // the address that answered last comes first
        val old = JSONObject(p.toJson().toString()).apply { remove("last_known"); remove("manual") } // saved before discovery
        assertNull(PeerPairing.fromJson(old).lastKnown)
    }
}
