package me.him188.ani.syncplay.engine

import me.him188.ani.syncplay.protocol.wire.IgnoringOnTheFlyData
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [processIgnoringOnTheFly] -- the anti-feedback gate that decides
 * whether an inbound `State` should be processed or skipped based on the
 * `ignoringOnTheFly` counters.
 *
 * Each test pins one branch of the decision tree. The regression test at the
 * end exercises the full local-change -> server-echo ACK loop to prove the
 * counter resets and does not re-trigger the reaction algorithm.
 */
class IgnoringOnTheFlyProcessorTest {

    @Test
    fun process_when_client_echo_matches() {
        // clientIgnFly=1, ignFly.client=1 -> match -> Process, counter resets to 0
        val result = processIgnoringOnTheFly(
            ignFly = IgnoringOnTheFlyData(client = 1),
            clientIgnFly = 1,
            serverIgnFly = 0,
        )

        assertEquals(IgnFlyResult.Process, result.result)
        assertEquals(0, result.newClientIgnFly)
        assertEquals(0, result.newServerIgnFly)
    }

    @Test
    fun skip_when_client_echo_mismatches() {
        // clientIgnFly=2, ignFly.client=1 -> mismatch -> Skip, counter unchanged
        val result = processIgnoringOnTheFly(
            ignFly = IgnoringOnTheFlyData(client = 1),
            clientIgnFly = 2,
            serverIgnFly = 0,
        )

        assertEquals(IgnFlyResult.Skip, result.result)
        assertEquals(2, result.newClientIgnFly)
        assertEquals(0, result.newServerIgnFly)
    }

    @Test
    fun process_when_server_ignFly_set() {
        // ignFly.server=1 -> server acknowledges our change -> Process,
        // serverIgnFly = 1, clientIgnFly resets to 0
        val result = processIgnoringOnTheFly(
            ignFly = IgnoringOnTheFlyData(server = 1),
            clientIgnFly = 1,
            serverIgnFly = 0,
        )

        assertEquals(IgnFlyResult.Process, result.result)
        assertEquals(0, result.newClientIgnFly)
        assertEquals(1, result.newServerIgnFly)
    }

    @Test
    fun process_when_no_ignFly_block_and_clientIgnFly_zero() {
        // ignFly=null, clientIgnFly=0 -> Process (normal state, no pending echo)
        val result = processIgnoringOnTheFly(
            ignFly = null,
            clientIgnFly = 0,
            serverIgnFly = 0,
        )

        assertEquals(IgnFlyResult.Process, result.result)
        assertEquals(0, result.newClientIgnFly)
        assertEquals(0, result.newServerIgnFly)
    }

    @Test
    fun skip_when_no_ignFly_block_and_clientIgnFly_nonzero() {
        // ignFly=null, clientIgnFly=1 -> Skip (still waiting for echo)
        val result = processIgnoringOnTheFly(
            ignFly = null,
            clientIgnFly = 1,
            serverIgnFly = 0,
        )

        assertEquals(IgnFlyResult.Skip, result.result)
        assertEquals(1, result.newClientIgnFly)
        assertEquals(0, result.newServerIgnFly)
    }

    @Test
    fun regression_local_pause_then_server_echo_resets_counter() {
        // Full ACK loop: a local pause increments clientIgnFly to 1, then the
        // server echoes back with ignFly.server=1. The counter must reset to 0
        // and the state must be Processed (no re-trigger of the reaction algo).

        // Step 1: local pause happened -> buildStatePacket(isLocalStateChange=true)
        // incremented clientIgnFly from 0 to 1. The next inbound State from the
        // server carries ignFly.server=1 to acknowledge our change.
        val afterEcho = processIgnoringOnTheFly(
            ignFly = IgnoringOnTheFlyData(server = 1),
            clientIgnFly = 1,
            serverIgnFly = 0,
        )

        // Counter resets, state is processed (diff ~ 0 -> NoOp, no re-trigger).
        assertEquals(IgnFlyResult.Process, afterEcho.result)
        assertEquals(0, afterEcho.newClientIgnFly)
        assertEquals(1, afterEcho.newServerIgnFly)

        // Step 2: the next periodic State has no ignFly block. With clientIgnFly
        // now 0, normal processing resumes.
        val nextPeriodic = processIgnoringOnTheFly(
            ignFly = null,
            clientIgnFly = afterEcho.newClientIgnFly,
            serverIgnFly = afterEcho.newServerIgnFly,
        )

        assertEquals(IgnFlyResult.Process, nextPeriodic.result)
        assertEquals(0, nextPeriodic.newClientIgnFly)
        assertEquals(1, nextPeriodic.newServerIgnFly)
    }
}
