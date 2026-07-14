package me.him188.ani.syncplay.engine

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.boolean
import me.him188.ani.syncplay.protocol.syncplayJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [ProtocolManager.buildStatePacket] and the `ignoringOnTheFly` counter logic.
 */
class ProtocolManagerTest {

    @Test
    fun buildStatePacket_basic() {
        val pm = ProtocolManager()
        val message = pm.buildStatePacket(
            serverTime = null,
            doSeek = true,
            position = 12.0,
            isLocalStateChange = false,
            play = false
        )

        // Verify object fields
        val playstate = message.data.playstate
        assertNotNull(playstate)
        assertEquals(12.0, playstate.position)
        assertEquals(true, playstate.paused)
        assertEquals(true, playstate.doSeek)
        assertNotNull(message.data.ping)

        // Verify JSON output
        val json = syncplayJson.parseToJsonElement(message.toJson()).jsonObject
        val stateObj = json["State"]!!.jsonObject
        val playstateObj = stateObj["playstate"]!!.jsonObject
        assertEquals(12.0, playstateObj["position"]!!.jsonPrimitive.double)
        assertEquals(true, playstateObj["paused"]!!.jsonPrimitive.boolean)
        assertEquals(true, playstateObj["doSeek"]!!.jsonPrimitive.boolean)
        assertNotNull(stateObj["ping"])
    }

    @Test
    fun buildStatePacket_localStateChange_incrementsClientIgnFly() {
        val pm = ProtocolManager()
        assertEquals(0, pm.clientIgnFly)

        pm.buildStatePacket(
            serverTime = null,
            doSeek = null,
            position = 5.0,
            isLocalStateChange = true,
            play = true
        )

        assertEquals(1, pm.clientIgnFly)

        pm.buildStatePacket(
            serverTime = null,
            doSeek = null,
            position = 5.0,
            isLocalStateChange = true,
            play = true
        )

        assertEquals(2, pm.clientIgnFly)
    }

    @Test
    fun buildStatePacket_serverIgnFly_reset() {
        val pm = ProtocolManager()
        pm.serverIgnFly = 1

        val message = pm.buildStatePacket(
            serverTime = null,
            doSeek = null,
            position = 5.0,
            isLocalStateChange = false,
            play = true
        )

        // serverIgnFly should be reset to 0 after the packet is built
        assertEquals(0, pm.serverIgnFly)

        // The snapshot value (1) should appear in the output
        val ignoring = message.data.ignoringOnTheFly
        assertNotNull(ignoring)
        assertEquals(1, ignoring.server)
    }

    @Test
    fun buildStatePacket_no_playstate_when_clientIgnFly_set() {
        val pm = ProtocolManager()
        pm.clientIgnFly = 1

        val message = pm.buildStatePacket(
            serverTime = null,
            doSeek = true,
            position = 10.0,
            isLocalStateChange = false,
            play = true
        )

        // playstate should be null because clientIgnFly != 0 suppresses it
        assertNull(message.data.playstate)

        // ping should still be present
        assertNotNull(message.data.ping)
    }

    @Test
    fun buildStatePacket_negative_position_guarded() {
        val pm = ProtocolManager()

        // Negative position should not crash and should be clamped to 0.0
        val messageNeg = pm.buildStatePacket(
            serverTime = null,
            doSeek = false,
            position = -1.0,
            isLocalStateChange = false,
            play = false
        )
        val playstateNeg = messageNeg.data.playstate
        assertNotNull(playstateNeg)
        assertEquals(0.0, playstateNeg.position)

        // NaN position should not crash and should be clamped to 0.0
        val pm2 = ProtocolManager()
        val messageNan = pm2.buildStatePacket(
            serverTime = null,
            doSeek = false,
            position = Double.NaN,
            isLocalStateChange = false,
            play = false
        )
        val playstateNan = messageNan.data.playstate
        assertNotNull(playstateNan)
        assertEquals(0.0, playstateNan.position)
    }
}
