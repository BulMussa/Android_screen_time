package com.screentime.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/** When the laptop is tried, and the exact status texts. */
class SyncPolicyTest {
    private val now = LocalTime.of(12, 30)
    private val previous = "Synced at 11:45 · laptop found via last known address (192.168.1.71)"
    private val found = "via broadcast (192.168.1.71)"

    private fun status(vararg outcomes: Outcome) = SyncStatus.text(outcomes.toList(), previous, now)

    private fun direct(error: SyncException?, trigger: Trigger = Trigger.MANUAL, note: String? = null) =
        Outcome("direct", 0, error, note, quiet = error != null && DirectPolicy.quiet(trigger, error))

    // --- The status texts the task names: success, no network, laptop off, wrong secret ---

    @Test
    fun success() {
        assertEquals("Synced at 12:30 · laptop found via broadcast (192.168.1.71)", status(direct(null, note = found)))
    }

    @Test
    fun noNetwork() {
        assertEquals(
            "Not on Wi-Fi, so the laptop can't be reached. It syncs again when the phone is on Wi-Fi.",
            status(direct(NoWifiException())),
        )
    }

    @Test
    fun laptopOff() {
        assertEquals(
            "Laptop not found. Is it on, with Screen Time running and Allow phone sync ticked, on the same Wi-Fi? Will try again.",
            status(direct(LaptopNotFoundException(), Trigger.MANUAL)),
        )
        assertEquals("opening the app says so too", status(direct(LaptopNotFoundException(), Trigger.MANUAL)),
            status(direct(LaptopNotFoundException(), Trigger.OPEN)))
    }

    @Test
    fun wrongPairingSecret() {
        assertEquals(
            "The laptop didn't accept the pairing code: it's wrong, expired or already used. Pair again with a new code.",
            status(direct(PeerAuthException(pairing = true))),
        )
    }

    @Test
    fun wrongKey() {
        assertEquals(
            "The laptop doesn't accept this phone anymore (the pairing was removed or replaced there). Pair again.",
            status(direct(PeerAuthException(pairing = false))),
        )
    }

    @Test
    fun serverModeIsUnchanged() {
        assertEquals("Synced at 12:30", status(Outcome("server", 0, null)))
        assertEquals("Synced at 12:30, 2 rows rejected (is the phone's clock right?)", status(Outcome("server", 2, null)))
        assertEquals("Offline, will try again. Can't reach the server (timeout)",
            status(Outcome("server", 0, SyncException("Can't reach the server (timeout)"))))
        assertEquals("The server doesn't accept this phone anymore. Disconnect and connect again.",
            status(Outcome("server", 0, SyncException("bad token", 401))))
        assertEquals("Not connected", status(Outcome("server", 0, NotSetUpException("Not connected"))))
    }

    // --- Quiet in the background ---

    @Test
    fun backgroundNotFoundKeepsThePreviousStatus() {
        assertEquals(previous, status(direct(LaptopNotFoundException(), Trigger.BACKGROUND)))
        assertEquals(previous, status(direct(SkippedException("not on Wi-Fi"), Trigger.BACKGROUND)))
        assertEquals(previous, status(direct(SkippedException("backing off"), Trigger.BACKGROUND)))
    }

    @Test
    fun backgroundStillReportsWhatNeedsTheUser() {
        // A wrong key or certificate won't fix itself, so the background says so
        assertFalse(DirectPolicy.quiet(Trigger.BACKGROUND, PeerAuthException(pairing = false)))
        assertFalse(DirectPolicy.quiet(Trigger.BACKGROUND, PeerTrustException("certificate")))
        assertFalse(DirectPolicy.quiet(Trigger.BACKGROUND, LocalNetworkException()))
        assertTrue(status(direct(PeerAuthException(false), Trigger.BACKGROUND)).startsWith("The laptop doesn't accept"))
    }

    @Test
    fun foregroundIsNeverQuiet() {
        for (t in listOf(Trigger.MANUAL, Trigger.OPEN)) {
            assertFalse(DirectPolicy.quiet(t, LaptopNotFoundException()))
            assertFalse(DirectPolicy.quiet(t, NoWifiException()))
        }
    }

    // --- Both: the laptop first, then the server ---

    @Test
    fun bothOrderIsLaptopThenServer() {
        assertEquals(listOf("direct", "server"), SyncEngine.MODES.getValue("both"))
        assertEquals(listOf("direct"), SyncEngine.MODES.getValue("direct"))
        assertEquals(listOf("server"), SyncEngine.MODES.getValue("server"))
    }

    @Test
    fun bothSucceeding() {
        assertEquals("Synced at 12:30 · laptop found via broadcast (192.168.1.71)",
            status(direct(null, note = found), Outcome("server", 0, null)))
    }

    @Test
    fun bothWithTheLaptopOffInTheForeground() {
        assertEquals(
            "Laptop: Laptop not found. Is it on, with Screen Time running and Allow phone sync ticked, on the same Wi-Fi? " +
                "Will try again. Server: synced at 12:30.",
            status(direct(LaptopNotFoundException()), Outcome("server", 0, null)),
        )
    }

    @Test
    fun bothWithTheLaptopQuietInTheBackground() {
        assertEquals("Synced with the server at 12:30",
            status(direct(LaptopNotFoundException(), Trigger.BACKGROUND), Outcome("server", 0, null)))
        assertEquals("Offline, will try again. Can't reach the server (timeout)",
            status(direct(SkippedException("not on Wi-Fi"), Trigger.BACKGROUND),
                Outcome("server", 0, SyncException("Can't reach the server (timeout)"))))
    }

    // --- When the background tries ---

    @Test
    fun foregroundAlwaysTries() {
        for (t in listOf(Trigger.MANUAL, Trigger.OPEN)) {
            assertEquals(DirectPolicy.Decision.TRY, DirectPolicy.decide(t, onWifi = false, misses = 9, lastAttemptMs = 0, nowMs = 0))
        }
    }

    @Test
    fun backgroundOnlyOnWifi() {
        assertEquals(DirectPolicy.Decision.SKIP_NO_WIFI, DirectPolicy.decide(Trigger.BACKGROUND, false, 0, 0, 1_000_000))
        assertEquals(DirectPolicy.Decision.TRY, DirectPolicy.decide(Trigger.BACKGROUND, true, 0, 0, 1_000_000))
    }

    @Test
    fun backoffDoublesAfterRepeatedMisses() {
        val minute = 60_000L
        assertEquals(0L, DirectPolicy.waitAfter(0))
        assertEquals(0L, DirectPolicy.waitAfter(1)) // one miss: the next period tries again
        assertEquals(30 * minute, DirectPolicy.waitAfter(2))
        assertEquals(60 * minute, DirectPolicy.waitAfter(3))
        assertEquals(120 * minute, DirectPolicy.waitAfter(4))
        assertEquals(240 * minute, DirectPolicy.waitAfter(5))
        assertEquals(240 * minute, DirectPolicy.waitAfter(50)) // never more than 4 hours
    }

    @Test
    fun backgroundWaitsOutTheBackoff() {
        val last = 10_000_000L
        val wait = DirectPolicy.waitAfter(3)
        assertEquals(DirectPolicy.Decision.SKIP_BACKING_OFF, DirectPolicy.decide(Trigger.BACKGROUND, true, 3, last, last + wait - 1))
        assertEquals(DirectPolicy.Decision.TRY, DirectPolicy.decide(Trigger.BACKGROUND, true, 3, last, last + wait))
        // Sync now isn't held back by the background's backoff
        assertEquals(DirectPolicy.Decision.TRY, DirectPolicy.decide(Trigger.MANUAL, true, 3, last, last + 1))
    }

    // --- Local network permission ---

    @Test
    fun localNetworkPermissionOnlyForTarget37OnAndroid17() {
        assertFalse("this app today: target 36", LocalNetwork.required(sdkInt = 37, targetSdk = 36))
        assertFalse(LocalNetwork.required(sdkInt = 36, targetSdk = 36))
        assertFalse("an older phone", LocalNetwork.required(sdkInt = 34, targetSdk = 37))
        assertTrue(LocalNetwork.required(sdkInt = 37, targetSdk = 37))
        assertEquals("android.permission.ACCESS_LOCAL_NETWORK", LocalNetwork.PERMISSION)
    }
}
