package me.him188.ani.syncplay.protocol.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [PingService] — RTT smoothing (EMA) and asymmetry-aware `forwardDelay`.
 * These guard the latency-compensation math the `State` handler uses to bias the global
 * position by message age.
 *
 * Because [PingService.receiveMessage] takes the wall-clock "now" as a parameter
 * (`currentTimestampMillis`), every test is fully deterministic: the test supplies
 * the same `now` the function uses, so there is no scheduling drift and assertions
 * can be exact rather than range-based.
 */
class PingServiceTest {

    /** A fixed "now" (epoch millis) used across samples so RTT = now/1000 - timestamp is exact. */
    private val nowMillis = 1_000_000L // → 1000.0 seconds

    /** Builds a fractional-second timestamp that is [secondsAgo] before [nowMillis]. */
    private fun timestampSecondsAgo(secondsAgo: Double): Double =
        nowMillis / 1000.0 - secondsAgo

    @Test
    fun `no samples leaves rtt and forwardDelay at initial zero without NaN`() {
        val ps = PingService()
        assertEquals(0.0, ps.rtt)
        assertEquals(0.0, ps.forwardDelay)
        assertFalse(ps.rtt.isNaN())
        assertFalse(ps.forwardDelay.isNaN())
    }

    @Test
    fun `null timestamp is a no-op`() {
        val ps = PingService()
        ps.receiveMessage(nowMillis, timestamp = null, senderRtt = 0.0)
        assertEquals(0.0, ps.rtt)
        assertEquals(0.0, ps.forwardDelay)
    }

    @Test
    fun `negative computed RTT is rejected`() {
        val ps = PingService()
        // Timestamp 60s in the future → rtt = 1000.0 - 1060.0 = -60.0 < 0 → early return.
        val futureTimestamp = nowMillis / 1000.0 + 60.0
        ps.receiveMessage(nowMillis, timestamp = futureTimestamp, senderRtt = 0.0)
        assertEquals(0.0, ps.forwardDelay, "forwardDelay should still be the initial 0.0")
    }

    @Test
    fun `negative senderRtt is rejected`() {
        val ps = PingService()
        ps.receiveMessage(nowMillis, timestamp = timestampSecondsAgo(1.0), senderRtt = -0.1)
        assertEquals(0.0, ps.forwardDelay, "negative senderRtt should not update state")
    }

    @Test
    fun `first message seeds forwardDelay to half the observed RTT in the symmetric path`() {
        val ps = PingService()
        // senderRtt high enough to keep us in the symmetric branch on the first ping
        // (forwardDelay = avrRtt / 2). Using a small senderRtt would fall into the
        // asymmetric branch and inflate forwardDelay by (rtt - senderRtt).
        ps.receiveMessage(nowMillis, timestamp = timestampSecondsAgo(2.0), senderRtt = 100.0)
        // rtt = 1000.0 - 998.0 = 2.0; avrRtt seeded to 2.0; forwardDelay = 2.0 / 2 = 1.0.
        assertEquals(2.0, ps.rtt, "rtt")
        assertEquals(1.0, ps.forwardDelay, "forwardDelay (= rtt / 2)")
    }

    @Test
    fun `first message applies asymmetry compensation when senderRtt is lower`() {
        val ps = PingService()
        // senderRtt < rtt → asymmetric branch even on the very first ping, matching
        // python which applies the asymmetry term from the first sample.
        ps.receiveMessage(nowMillis, timestamp = timestampSecondsAgo(2.0), senderRtt = 0.5)
        // rtt = 2.0; avrRtt seeded to 2.0;
        // forwardDelay = avrRtt / 2 + (rtt - senderRtt) = 1.0 + 1.5 = 2.5.
        assertEquals(2.5, ps.forwardDelay, "asymmetric forwardDelay = avrRtt/2 + (rtt - senderRtt)")
        assertTrue(
            ps.forwardDelay > ps.rtt / 2,
            "asymmetric path should add (rtt - senderRtt) on first ping: rtt=${ps.rtt}, fd=${ps.forwardDelay}"
        )
    }

    @Test
    fun `EMA pulls a sudden spike partially toward the moving average`() {
        val ps = PingService()
        // senderRtt high enough to keep us in the symmetric branch
        // (forwardDelay = avrRtt / 2) — otherwise asymmetry inflates the reading.
        ps.receiveMessage(nowMillis, timestamp = timestampSecondsAgo(1.0), senderRtt = 100.0)
        val seededDelay = ps.forwardDelay // = 0.5
        ps.receiveMessage(nowMillis, timestamp = timestampSecondsAgo(5.0), senderRtt = 100.0)

        // After the spike: avrRtt = 1.0*0.85 + 5.0*0.15 = 0.85 + 0.75 = 1.6
        // forwardDelay = 1.6 / 2 = 0.8 — well below the un-smoothed spike-half (5.0/2 = 2.5).
        assertEquals(0.8, ps.forwardDelay, 1e-9, "forwardDelay should be avrRtt/2 = 0.8 after EMA")
        assertTrue(
            ps.forwardDelay > seededDelay,
            "forwardDelay should increase after a spike: was=$seededDelay now=${ps.forwardDelay}"
        )
        assertTrue(
            ps.forwardDelay < 2.5,
            "EMA should keep forwardDelay below the un-smoothed spike-half: got ${ps.forwardDelay}"
        )
    }

    @Test
    fun `three samples pull the smoothed RTT toward their mean`() {
        val ps = PingService()
        // Symmetric path (senderRtt high) so forwardDelay = avrRtt / 2 purely.
        // Samples: rtt = 1.0, 3.0, 2.0 (mean = 2.0).
        ps.receiveMessage(nowMillis, timestamp = timestampSecondsAgo(1.0), senderRtt = 100.0) // rtt = 1.0
        val firstForwardDelay = ps.forwardDelay // = 0.5
        ps.receiveMessage(nowMillis, timestamp = timestampSecondsAgo(3.0), senderRtt = 100.0) // rtt = 3.0
        ps.receiveMessage(nowMillis, timestamp = timestampSecondsAgo(2.0), senderRtt = 100.0) // rtt = 2.0
        // avrRtt: seed=1.0 → 1.0*0.85+3.0*0.15=1.3 → 1.3*0.85+2.0*0.15=1.105+0.3=1.405
        // forwardDelay = 1.405 / 2 = 0.7025
        assertEquals(0.7025, ps.forwardDelay, 1e-9, "forwardDelay should be avrRtt/2 = 0.7025")
        // Converging upward toward mean/2 (= 1.0) but EMA with weight 0.85 is slow.
        assertTrue(ps.forwardDelay > firstForwardDelay, "should move toward mean after more samples")
        assertTrue(ps.forwardDelay < 1.0, "after 3 samples EMA is still below mean/2")
    }

    @Test
    fun `asymmetric path adds extra delay when client RTT exceeds server RTT`() {
        val symmetric = PingService().also {
            it.receiveMessage(nowMillis, timestamp = timestampSecondsAgo(1.0), senderRtt = 1.0)
            // Symmetric second sample: rtt = 3.0, senderRtt = 3.0 → forwardDelay = avrRtt / 2.
            it.receiveMessage(nowMillis, timestamp = timestampSecondsAgo(3.0), senderRtt = 3.0)
        }
        val asymmetric = PingService().also {
            it.receiveMessage(nowMillis, timestamp = timestampSecondsAgo(1.0), senderRtt = 1.0)
            // Asymmetric: rtt = 3.0, senderRtt = 1.0 → forwardDelay = avrRtt / 2 + (rtt - senderRtt).
            it.receiveMessage(nowMillis, timestamp = timestampSecondsAgo(3.0), senderRtt = 1.0)
        }
        // Both: avrRtt = 1.0*0.85 + 3.0*0.15 = 1.3
        // symmetric forwardDelay = 1.3 / 2 = 0.65
        // asymmetric forwardDelay = 1.3 / 2 + (3.0 - 1.0) = 0.65 + 2.0 = 2.65
        // delta = 2.65 - 0.65 = 2.0 ≈ (rtt - senderRtt).
        val delta = asymmetric.forwardDelay - symmetric.forwardDelay
        assertEquals(2.0, delta, 1e-9, "asymmetric extra delay should be ~ (rtt - senderRtt)")
    }

    @Test
    fun `symmetric path stays at half the moving average when senderRtt is greater than rtt`() {
        val ps = PingService()
        ps.receiveMessage(nowMillis, timestamp = timestampSecondsAgo(1.0), senderRtt = 1.0) // seed
        ps.receiveMessage(nowMillis, timestamp = timestampSecondsAgo(1.0), senderRtt = 10.0)
        // senderRtt >= rtt → else branch: forwardDelay = avrRtt / 2.
        // avrRtt = 1.0*0.85 + 1.0*0.15 = 1.0 → forwardDelay = 0.5.
        assertEquals(0.5, ps.forwardDelay, 1e-9, "symmetric forwardDelay should be ~rtt/2")
    }

    @Test
    fun `repeated identical RTTs converge to half the RTT`() {
        val ps = PingService()
        // senderRtt = 100 keeps us in the symmetric branch on every sample, so the
        // result reflects pure EMA convergence (no asymmetry term).
        repeat(50) { ps.receiveMessage(nowMillis, timestamp = timestampSecondsAgo(1.0), senderRtt = 100.0) }
        // Each sample's rtt = 1.0 exactly; avrRtt is a fixed point at 1.0 → forwardDelay = 0.5.
        assertEquals(0.5, ps.forwardDelay, 1e-9, "forwardDelay should converge to rtt/2 = 0.5")
    }
}
