package me.him188.ani.syncplay.engine

import kotlinx.coroutines.flow.update
import me.him188.ani.syncplay.network.SyncplayNetworkManager
import me.him188.ani.syncplay.protocol.WireMessage
import me.him188.ani.syncplay.protocol.WireMessageHandler
import me.him188.ani.syncplay.protocol.models.MediaFile
import me.him188.ani.syncplay.protocol.models.User
import me.him188.ani.syncplay.protocol.wire.ListUserData
import me.him188.ani.syncplay.protocol.wire.UserSetData
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Client-side implementation of [WireMessageHandler].
 *
 * Owns the room-level reactions to every incoming server-bound [WireMessage] — playback
 * synchronization, user-list rendering, chat broadcasts, TLS upgrade, etc. Only the
 * server→client variants are overridden; the client→server ones inherit the no-op
 * defaults.
 *
 * Ported from syncplay-mobile `RoomServerMessageHandler`, stripped of viewmodel
 * dependencies (player, preferences, OSD, resource strings). The player-dependent
 * parameters of [decideAction] (`localPositionSec`, `mediaLoaded`, `canFastForward`)
 * are stubbed here (0.0 / false / false) until the player bridge (T4.x) supplies them.
 *
 * @param session the connection's session state
 * @param protocol the protocol manager holding global playback state
 * @param callback the room event callback (player/UI notifications)
 * @param networkManager the network transport for outbound messages
 */
class SyncplayMessageHandler(
    private val session: Session,
    private val protocol: ProtocolManager,
    private val callback: RoomCallback,
    private val networkManager: SyncplayNetworkManager,
) : WireMessageHandler {

    override suspend fun onHello(message: WireMessage.Hello) {
        val data = message.data
        data.username?.let { session.currentUsername = it }
        session.roomFeatures = data.features

        networkManager.onConnected()
        callback.onConnected()
        networkManager.send(WireMessage.listRequest())
    }

    override suspend fun onSet(message: WireMessage.Set) {
        val set = message.data

        set.user?.let { handleUserSet(it) }
        set.room?.let { session.currentRoom = it.name }
        set.ready?.let { handleReadySet(it) }
        set.features?.let { session.roomFeatures = it }
    }

    override suspend fun onListResponse(message: WireMessage.ListResponse) {
        val userlist = message.rooms[session.currentRoom] ?: return
        val newList = buildList {
            var indexer = 1
            for ((userName, userData) in userlist) {
                val user = buildUserFromListData(userName, userData, indexer)
                if (userName != session.currentUsername) indexer++
                add(user)
            }
        }
        session.userList.value = newList
    }

    override suspend fun onChatBroadcast(message: WireMessage.ChatBroadcast) {
        val sender = message.data.username ?: return
        val text = message.data.message ?: return

        callback.onChatReceived(sender, text)
        session.messageSequence.update { it + ChatMessage(username = sender, message = text) }
    }

    override suspend fun onTLS(message: WireMessage.TLS) {
        val startTLS = message.data.startTLS ?: return
        val supported = startTLS.contains("true", ignoreCase = true)
        if (supported) {
            networkManager.upgradeTls()
        }
        networkManager.onReadyForHandshake()
    }

    override suspend fun onError(message: WireMessage.Error) {
        val errorText = message.data.message ?: return
        session.messageSequence.update {
            it + ChatMessage(username = "", message = errorText, isSystemMessage = true)
        }
    }

    /**
     * The full reaction-algorithm handler. Stamps the freshness watchdog, processes the
     * `ignoringOnTheFly` anti-feedback gate, runs [decideAction] with the extracted
     * state, applies the returned [SyncAction]s via [RoomCallback], updates
     * [ProtocolManager] global state, and ACKs with [ProtocolManager.buildStatePacket].
     *
     * Ported from syncplay-mobile `RoomServerMessageHandler.onState` (lines 65-275),
     * using the pure functions [processIgnoringOnTheFly] (T3.3) and [decideAction]
     * (T3.2) instead of the inline logic.
     */
    override suspend fun onState(message: WireMessage.State) {
        protocol.lastStateReceivedAt = Clock.System.now()

        val state = message.data

        val ignFlyResult = processIgnoringOnTheFly(
            ignFly = state.ignoringOnTheFly,
            clientIgnFly = protocol.clientIgnFly,
            serverIgnFly = protocol.serverIgnFly,
        )
        protocol.clientIgnFly = ignFlyResult.newClientIgnFly
        protocol.serverIgnFly = ignFlyResult.newServerIgnFly

        if (ignFlyResult.result == IgnFlyResult.Skip) return

        var position: Double? = null
        var paused: Boolean? = null
        var doSeek: Boolean? = null
        var setBy: String? = null
        var messageAge = 0.0
        var latencyCalculation: Double? = null

        state.playstate?.let { playstate ->
            position = playstate.position ?: 0.0
            paused = playstate.paused
            doSeek = playstate.doSeek
            setBy = playstate.setBy
        }

        state.ping?.let { ping ->
            latencyCalculation = ping.latencyCalculation
            ping.clientLatencyCalculation?.let { timestamp ->
                val serverRtt = ping.serverRtt ?: return@let
                protocol.pingService.receiveMessage(
                    Clock.System.now().toEpochMilliseconds(),
                    timestamp,
                    serverRtt,
                )
            }
            messageAge = protocol.pingService.forwardDelay
        }

        if (position != null && paused != null) {
            val pos = position
            val isPaused = paused
            val now = Clock.System.now()
            val actions = decideAction(
                paused = isPaused,
                position = pos,
                doSeek = doSeek,
                setBy = setBy,
                messageAge = messageAge,
                localPositionSec = 0.0,
                currentUsername = session.currentUsername,
                globalPaused = protocol.globalPaused,
                lastGlobalUpdate = protocol.lastGlobalUpdate,
                mediaLoaded = false,
                speedChanged = protocol.speedChanged,
                canFastForward = false,
                behindFirstDetected = protocol.behindFirstDetected,
                now = now,
            )

            applyActions(actions, setBy)

            val agedPosition = if (isPaused) pos else pos + messageAge
            protocol.globalPaused = isPaused
            protocol.globalPositionMs = agedPosition * 1000.0
            protocol.lastGlobalPositionSetAt = now
            protocol.lastGlobalUpdate = now

            val hasFastForward = actions.any { it is SyncAction.FastForward }
            if (hasFastForward) {
                protocol.behindFirstDetected = now + ProtocolManager.FASTFORWARD_RESET_THRESHOLD.seconds
            } else {
                protocol.behindFirstDetected = null
            }

            networkManager.sendAsync(
                protocol.buildStatePacket(
                    serverTime = latencyCalculation,
                    doSeek = null,
                    position = protocol.globalPositionMs / 1000.0,
                    isLocalStateChange = false,
                    play = !isPaused,
                )
            )
        } else {
            networkManager.sendAsync(
                protocol.buildStatePacket(
                    serverTime = latencyCalculation,
                    doSeek = null,
                    position = null,
                    isLocalStateChange = false,
                    play = null,
                )
            )
        }
    }

    /**
     * Translates each [SyncAction] into the appropriate [RoomCallback] call.
     *
     * `SlowDown`, `ResetSpeed`, and `NoOp` produce no callback — the player bridge
     * (T4.x) handles those directly.
     */
    private suspend fun applyActions(actions: List<SyncAction>, setBy: String?) {
        val who = setBy ?: ""
        for (action in actions) {
            when (action) {
                is SyncAction.Seek -> callback.onSomeoneSeeked(who, action.positionMs / 1000.0)
                is SyncAction.Rewind -> callback.onSomeoneBehind(who, action.positionSec)
                is SyncAction.FastForward -> callback.onSomeoneFastForwarded(who, action.positionSec)
                SyncAction.Pause -> callback.onSomeonePaused(who)
                SyncAction.Resume -> callback.onSomeonePlayed(who)
                is SyncAction.FirstSync -> {
                    callback.onSomeoneSeeked(who, action.positionMs / 1000.0)
                    if (action.paused) callback.onSomeonePaused(who) else callback.onSomeonePlayed(who)
                }
                is SyncAction.SlowDown, SyncAction.ResetSpeed, SyncAction.NoOp -> {
                    // No callback — player bridge (T4.x) handles these.
                }
            }
        }
    }

    // -----------------------------------------------------------
    // Set sub-routing
    // -----------------------------------------------------------

    /**
     * Mirrors python's `_SetUser`: mutates the local user list directly from the Set
     * we just received, instead of round-tripping a List request.
     *
     * File metadata inside [UserSetData] is not applied here — the file-identity-in-
     * filename feature is T4.3 scope.
     */
    private suspend fun handleUserSet(userMap: Map<String, UserSetData>) {
        for ((userName, userData) in userMap) {
            handleSingleUserSet(userName, userData)
        }
    }

    private suspend fun handleSingleUserSet(userName: String, userData: UserSetData) {
        val current = session.userList.value
        val updated = current.toMutableList()
        var changed = false

        val eventRoom = userData.room?.name
        val inOurRoom = eventRoom == null || eventRoom == session.currentRoom

        // T4.3: When a peer's file changes (Set.user[username].file), emit to
        // inboundFileFlow so the player bridge can parse identity-in-filename
        // and auto-switch episodes. Only process files from OTHER users —
        // our own file echoes are not relayed by the server.
        if (userData.file != null && userName != session.currentUsername) {
            session.emitInboundFile(userData.file)
        }

        userData.event?.let { event ->
            when {
                event.left != null -> {
                    val idx = updated.indexOfFirst { it.name == userName }
                    if (idx >= 0) {
                        updated.removeAt(idx)
                        changed = true
                        callback.onSomeoneLeft(userName)
                    }
                }
                event.joined != null && inOurRoom -> {
                    if (updated.none { it.name == userName }) {
                        val nextIndex = (updated.maxOfOrNull { it.index } ?: 0) + 1
                        updated.add(
                            User(
                                name = userName,
                                index = nextIndex,
                                readiness = false,
                                file = null,
                                isController = false,
                            )
                        )
                        changed = true
                    }
                    callback.onSomeoneJoined(userName)
                }
            }
        }

        if (userData.event == null && eventRoom != null && userName != session.currentUsername) {
            val idx = updated.indexOfFirst { it.name == userName }
            if (!inOurRoom && idx >= 0) {
                updated.removeAt(idx)
                changed = true
            } else if (inOurRoom && idx < 0) {
                val nextIndex = (updated.maxOfOrNull { it.index } ?: 0) + 1
                updated.add(
                    User(
                        name = userName,
                        index = nextIndex,
                        readiness = false,
                        file = null,
                        isController = false,
                    )
                )
                changed = true
                callback.onSomeoneJoined(userName)
            }
        }

        if (changed) session.userList.value = updated
    }

    /**
     * Mirrors python's `setReady`: toggles the user's readiness flag in the local user
     * list. Stripped of the controller-set-others-readiness OSD announcement (no
     * resource strings in this layer).
     */
    private suspend fun handleReadySet(ready: me.him188.ani.syncplay.protocol.wire.ReadyData) {
        val userName = ready.username ?: return
        val isReady = ready.isReady ?: return

        val current = session.userList.value
        val idx = current.indexOfFirst { it.name == userName }
        if (idx < 0) return
        if (current[idx].readiness == isReady) return

        val updated = current.toMutableList()
        updated[idx] = updated[idx].copy(readiness = isReady)
        session.userList.value = updated

        if (userName == session.currentUsername) {
            session.ready.value = isReady
        }
    }

    private fun buildUserFromListData(userName: String, userData: ListUserData, indexer: Int): User {
        return User(
            name = userName,
            index = if (userName != session.currentUsername) indexer else 0,
            readiness = userData.isReady ?: false,
            file = userData.file?.let { fileData ->
                if (fileData.name != null) {
                    MediaFile(
                        fileName = fileData.name,
                        fileDuration = fileData.duration,
                        fileSize = fileData.size ?: "",
                    )
                } else null
            },
            isController = userData.controller,
        )
    }
}
